/*
 * Create a graphviz graph based on the classes in the specified java
 * source files.
 *
 * (C) Copyright 2002-2005 Diomidis Spinellis
 *
 * Permission to use, copy, and distribute this software and its
 * documentation for any purpose and without fee is hereby granted,
 * provided that the above copyright notice appear in all copies and that
 * both that copyright notice and this permission notice appear in
 * supporting documentation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND WITHOUT ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTIES OF
 * MERCHANTIBILITY AND FITNESS FOR A PARTICULAR PURPOSE.
 *
 *
 */

package org.umlgraph.doclet;

import static org.umlgraph.doclet.DocletUtil.enumConstantsIn;
import static org.umlgraph.doclet.DocletUtil.findTags;
import static org.umlgraph.doclet.DocletUtil.flatFindTags;
import static org.umlgraph.doclet.DocletUtil.flatText;
import static org.umlgraph.doclet.StringUtil.escape;
import static org.umlgraph.doclet.StringUtil.guilWrap;
import static org.umlgraph.doclet.StringUtil.guillemize;
import static org.umlgraph.doclet.StringUtil.htmlNewline;
import static org.umlgraph.doclet.StringUtil.removeTemplate;
import static org.umlgraph.doclet.StringUtil.tokenize;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;

import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.TextTree;

import jdk.javadoc.doclet.DocletEnvironment;

/**
 * Class graph generation engine
 * @depend - - - StringUtil
 * @depend - - - Options
 * @composed - - * ClassInfo
 * @has - - - OptionProvider
 *
 * @version $Revision$
 * @author <a href="http://www.spinellis.gr">Diomidis Spinellis</a>
 */
class ClassGraph implements AutoCloseable {
    protected static final char FILE_SEPARATOR = '/';

    enum Align {
	LEFT, CENTER, RIGHT;

	public final String lower;

	private Align() {
	    this.lower = toString().toLowerCase();
	}
    };

    protected Map<String, ClassInfo> classnames = new HashMap<String, ClassInfo>();
    protected Set<String> rootClasses;
	protected Map<String, TypeElement> rootClassdocs = new HashMap<String, TypeElement>();
    protected OptionProvider optionProvider;
    protected PrintWriter w;
    protected TypeElement collectionClassDoc;
    protected TypeElement mapClassDoc;
    protected String linePostfix;
    protected String linePrefix;
    final protected DocletEnvironment root;
    
    // used only when generating context class diagrams in UMLDoc, to generate the proper
    // relative links to other classes in the image map
    protected Element contextDoc;
      
    /**
     * Create a new ClassGraph.  <p>The packages passed as an
     * argument are the ones specified on the command line.</p>
     * <p>Local URLs will be generated for these packages.</p>
     * @param root The root of docs as provided by the javadoc API
     * @param optionProvider The main option provider
     * @param contextDoc The current context for generating relative links, may be a TypeElement 
     * 	or a PackageElement (used by UMLDoc)
     */
    public ClassGraph(DocletEnvironment root, OptionProvider optionProvider, Element contextDoc) {
	this.root = root;
	this.optionProvider = optionProvider;
	this.collectionClassDoc = root.getElementUtils().getTypeElement("java.util.Collection");
	this.mapClassDoc = root.getElementUtils().getTypeElement("java.util.Map");
	this.contextDoc = contextDoc;
	
	// to gather the packages containing specified classes, loop thru them and gather
	// package definitions. User root.specifiedPackages is not safe, since the user
	// may specify just a list of classes (human users usually don't, but automated tools do)
	rootClasses = new HashSet<String>();
	for (TypeElement classDoc : ElementFilter.typesIn(root.getIncludedElements())) {
	    rootClasses.add(classDoc.getQualifiedName().toString());
		rootClassdocs.put(classDoc.getQualifiedName().toString(), classDoc);
	}
	
	Options opt = optionProvider.getGlobalOptions();
	if (opt.compact) {
	    linePrefix = "";
	    linePostfix = "";
	} else {
	    linePrefix = "\t";
	    linePostfix = "\n";
	}
    }

    

    /** Return the class's name, possibly by stripping the leading path */
    private static String qualifiedName(Options opt, String r) {
	// Nothing to do:
	if (opt.showQualified && (opt.showQualifiedGenerics || r.indexOf('<') < 0))
	    return r;
	StringBuilder buf = new StringBuilder(r.length());
	int last = 0, depth = 0;
	boolean strip = !opt.showQualified;
	for (int i = 0; i < r.length();) {
	    char c = r.charAt(i++);
	    // The last condition prevents losing the dot in A<V>.B
	    if ((c == '.' || c == '$') && strip && last + 1 < i)
		last = i; // skip
	    if (Character.isJavaIdentifierPart(c))
		continue;
	    // Handle nesting of generics
	    if (c == '<') {
		++depth;
		strip = !opt.showQualifiedGenerics;
	    } else if (c == '>' && --depth == 0)
		strip = !opt.showQualified;
	    if (last < i) {
		buf.append(r, last, i);
		last = i;
	    }
	}
	if (last < r.length())
	    buf.append(r, last, r.length());
	return buf.toString();
    }

    /**
     * Print the visibility adornment of element e prefixed by
     * any stereotypes
     */
    private String visibility(Options opt, Element e) {
	return opt.showVisibility ? Visibility.get(e).symbol : " ";
    }

    /** Print the method parameter p */
    private String parameter(Options opt, List<? extends VariableElement> p) {
	StringBuilder par = new StringBuilder(1000);
	for (VariableElement v : p)
	    par.append(v.getSimpleName() + typeAnnotation(opt, v.asType())).append(", ");
	if (par.length() > 2)
	    par.setLength(par.length() - 2);
	return par.toString();
    }

    /** Print a a basic type t */
    private String type(Options opt, TypeMirror t, boolean generics) {
	String type;
	if (t.getKind() == TypeKind.DECLARED &&
		generics ? opt.showQualifiedGenerics : opt.showQualified)
	    type = ((TypeElement) root.getTypeUtils().asElement(t)).getQualifiedName().toString();
	else
	    type = t.toString();
	type += typeParameters(opt, t);
	return type;
    }

    /** Print the parameters of the parameterized type t */
    private String typeParameters(Options opt, TypeMirror t) {
	if (t == null || t.getKind() != TypeKind.DECLARED)
	    return "";
	List<? extends TypeParameterElement> args = ((TypeElement)t).getTypeParameters();
	if (args.isEmpty())
	    return "";
	StringBuilder tp = new StringBuilder(1000).append("&lt;");
	for (int i = 0; i < args.size(); i++) {
	    tp.append(type(opt, args.get(i).asType(), true));
	    if (i != args.size() - 1)
		tp.append(", ");
	}
	return tp.append("&gt;").toString();
    }

    /** Annotate an field/argument with its type t */
    private String typeAnnotation(Options opt, TypeMirror t) {
	if (t.getKind() == TypeKind.VOID)
	    return "";
	StringBuilder ta = new StringBuilder(1000).append(" : ");
	int dim = 0;
	while (t.getKind() == TypeKind.ARRAY) {
	    ++dim;
	    t = ((ArrayType) t).getComponentType();
	}
	ta.append(type(opt, t, false));
	for (int i = 0; i < dim; i++)
	    ta.append("[]");
	return ta.toString();
    }

    /** Print the class's attributes fd */
    private void attributes(Options opt, List<VariableElement> fd) {
	for (VariableElement f : fd) {
	    if (hidden(f))
		continue;
	    stereotype(opt, f, Align.LEFT);
	    String att = visibility(opt, f) + f.getSimpleName();
	    if (opt.showType)
		att += typeAnnotation(opt, f.asType());
	    tableLine(Align.LEFT, att);
	    tagvalue(opt, f);
	}
    }

    /*
     * The following two methods look similar, but can't
     * be refactored into one, because their common interface,
     * ExecutableMemberDoc, doesn't support returnType for ctors.
     */

    /** Print the class's constructors m */
    private boolean constructors(Options opt, List<ExecutableElement> m) {
	boolean printed = false;
	for (ExecutableElement cd : m) {
	    if (hidden(cd))
		continue;
	    stereotype(opt, cd, Align.LEFT);
	    String cs = visibility(opt, cd) + cd.getSimpleName() //
		    + (opt.showType ? "(" + parameter(opt, cd.getParameters()) + ")" : "()");
	    tableLine(Align.LEFT, cs);
	    tagvalue(opt, cd);
	    printed = true;
	}
	return printed;
    }

    /** Print the class's operations m */
    private boolean operations(Options opt, List<? extends ExecutableElement> m) {
	boolean printed = false;
	for (ExecutableElement md : m) {
	    if (hidden(md))
		continue;
	    // Filter-out static initializer method
	    if (md.getSimpleName().contentEquals("<clinit>") && md.getModifiers().contains(Modifier.STATIC)) // && md.isPackagePrivate())
		continue;
	    stereotype(opt, md, Align.LEFT);
	    String op = visibility(opt, md) + md.getSimpleName() + //
		    (opt.showType ? "(" + parameter(opt, md.getParameters()) + ")" + typeAnnotation(opt, md.getReturnType())
			    : "()");
	    tableLine(Align.LEFT, (md.getModifiers().contains(Modifier.ABSTRACT) ? Font.ABSTRACT : Font.NORMAL).wrap(opt, op));
	    printed = true;
	    tagvalue(opt, md);
	}
	return printed;
    }

    /** Print the common class node's properties */
    private void nodeProperties(Options opt) {
	w.print(", fontname=\"" + opt.nodeFontName + "\"");
	w.print(", fontcolor=\"" + opt.nodeFontColor + "\"");
	w.print(", fontsize=" + opt.nodeFontSize);
	w.print(opt.shape.style);
	w.println("];");
    }

    /**
     * Return as a string the tagged values associated with c
     * @param opt the Options used to guess font names
     * @param c the Element entry to look for @tagvalue
     * @param prevterm the termination string for the previous element
     * @param term the termination character for each tagged value
     */
    private void tagvalue(Options opt, Element c) {
	DocletUtil.findTags(root, c, "tagvalue") //
		.map(DocletUtil::flatText).forEach(text -> {
		    String t[] = StringUtil.tokenize(text);
		    if (t.length != 2) {
			System.err.println("@tagvalue expects two fields: " + text);
			return;
		    }
		    tableLine(Align.RIGHT, Font.TAG.wrap(opt, "{" + t[0] + " = " + t[1] + "}"));
		});
    }


    /**
     * Return as a string the stereotypes associated with c
     * terminated by the escape character term
     */
    private void stereotype(Options opt, Element c, Align align) {
	DocletUtil.findTags(root, c, "tagvalue") //
		.map(DocletUtil::flatText).forEach(text -> {
		    String t[] = StringUtil.tokenize(text);
		    if (t.length != 1) {
			System.err.println("@stereotype expects one field: " + text);
			return;
		    }
		    tableLine(align, guilWrap(opt, t[0]));
		});
    }

    /** Return true if c has a @hidden tag associated with it */
    private boolean hidden(Element c) {
	if (DocletUtil.findTags(root, c, "hidden").findFirst().isPresent())
	    return true;
	if (DocletUtil.findTags(root, c, "view").findFirst().isPresent())
	    return true;
	Options opt; 
	if(c instanceof TypeElement)
	    opt = optionProvider.getOptionsFor(root, (TypeElement) c);
	else if(c instanceof PackageElement)
	    opt = optionProvider.getOptionsFor(((PackageElement) c).getQualifiedName().toString());
	else
	    return false; // FIXME: else?
	return opt.matchesHideExpression(c.toString());
    }
    
    protected ClassInfo getClassInfo(String className) {
	return classnames.get(removeTemplate(className));
    }
    
    private ClassInfo newClassInfo(String className, boolean printed, boolean hidden) {
	ClassInfo ci = new ClassInfo(printed, hidden);
        classnames.put(removeTemplate(className), ci);
        return ci;
    }

    /** Return true if the class name is associated to an hidden class or matches a hide expression */
    private boolean hidden(String s) {
	ClassInfo ci = getClassInfo(s);
	return (ci != null && ci.hidden) || optionProvider.getOptionsFor(s).matchesHideExpression(s);
    }

    

    /**
     * Prints the class if needed.
     * <p>
     * A class is a rootClass if it's included among the classes returned by
     * DocletEnvironment.classes(), this information is used to properly compute
     * relative links in diagrams for UMLDoc
     */
    public String printClass(TypeElement c, boolean rootClass) {
	ClassInfo ci;
	boolean toPrint;
	Options opt = optionProvider.getOptionsFor(root, c);

	String className = c.toString();
	if ((ci = getClassInfo(className)) != null)
	    toPrint = !ci.nodePrinted;
	else {
	    toPrint = true;
	    ci = newClassInfo(className, true, hidden(c));
	}
	boolean isEnum = c.getKind() == ElementKind.ENUM;
	boolean isInterface = c.getKind() == ElementKind.INTERFACE;
	if (toPrint && !hidden(c) && (!isEnum || opt.showEnumerations)) {
	    // Associate classname's alias
	    String r = className;
	    w.println("\t// " + r);
	    // Create label
	    w.print("\t" + ci.name + " [label=");

	    // TODO: don't recompute these...
	    boolean showMembers =
		(opt.showAttributes && ElementFilter.fieldsIn(c.getEnclosedElements()).size() > 0) ||
		(isEnum && opt.showEnumConstants && enumConstantsIn(c.getEnclosedElements()).size() > 0) ||
		(opt.showOperations && ElementFilter.methodsIn(c.getEnclosedElements()).size() > 0) ||
		(opt.showConstructors && ElementFilter.constructorsIn(c.getEnclosedElements()).size() > 0);
	    
	    externalTableStart(opt, c.getQualifiedName().toString(), classToUrl(c, rootClass));

	    // Calculate the number of innerTable rows we will emit
	    int nRows = 1;
	    if (showMembers) {
		if (opt.showAttributes)
		    nRows++;
		else if(!isEnum && (opt.showConstructors || opt.showOperations))
		    nRows++;
		else if (isEnum && opt.showEnumConstants)
		    nRows++;
	    }

	    firstInnerTableStart(opt, nRows);
	    if (isInterface)
		tableLine(Align.CENTER, guilWrap(opt, "interface"));
	    if (isEnum)
		tableLine(Align.CENTER, guilWrap(opt, "enumeration"));
	    stereotype(opt, c, Align.CENTER);
	    Font font = c.getModifiers().contains(Modifier.ABSTRACT) && !isInterface ? Font.CLASS_ABSTRACT : Font.CLASS;
	    String qualifiedName = qualifiedName(opt, r);
	    int startTemplate = qualifiedName.indexOf('<');
	    int idx = qualifiedName.lastIndexOf('.', startTemplate < 0 ? qualifiedName.length() - 1 : startTemplate);
	    if (opt.showComment)
		tableLine(Align.LEFT, Font.CLASS.wrap(opt, htmlNewline(escape(flatText(root.getDocTrees().getDocCommentTree(c).getFullBody())))));
	    else if (opt.postfixPackage && idx > 0 && idx < (qualifiedName.length() - 1)) {
		String packageName = qualifiedName.substring(0, idx);
		String cn = qualifiedName.substring(idx + 1);
		tableLine(Align.CENTER, font.wrap(opt, escape(cn)));
		tableLine(Align.CENTER, Font.PACKAGE.wrap(opt, packageName));
	    } else {
		tableLine(Align.CENTER, font.wrap(opt, escape(qualifiedName)));
	    }
	    tagvalue(opt, c);
	    firstInnerTableEnd(opt, nRows);
	    
	    /*
	     * Warning: The boolean expressions guarding innerTableStart()
	     * in this block, should match those in the code block above
	     * marked: "Calculate the number of innerTable rows we will emmit"
	     */
	    if (showMembers) {
		if (opt.showAttributes) {
		    innerTableStart();
		    List<VariableElement> fields = ElementFilter.fieldsIn(c.getEnclosedElements());
		    // if there are no fields, print an empty line to generate proper HTML
		    if (fields.isEmpty())
			tableLine(Align.LEFT, "");
		    else
			attributes(opt, fields);
		    innerTableEnd();
		} else if(!isEnum && (opt.showConstructors || opt.showOperations)) {
		    // show an emtpy box if we don't show attributes but
		    // we show operations
		    innerTableStart();
		    tableLine(Align.LEFT, "");
		    innerTableEnd();
	    	}
		if (isEnum && opt.showEnumConstants) {
		    innerTableStart();
		    List<VariableElement> ecs = ElementFilter.fieldsIn(c.getEnclosedElements());
		    // if there are no constants, print an empty line to generate proper HTML		    
		    if (ecs.size() == 0) {
			tableLine(Align.LEFT, "");
		    } else {
			for (VariableElement fd : ecs) {
			    tableLine(Align.LEFT, fd.getSimpleName());
			}
		    }
		    innerTableEnd();
		}
		if (!isEnum && (opt.showConstructors || opt.showOperations)) {
		    innerTableStart();
		    boolean printedLines = false;
		    if (opt.showConstructors)
			printedLines |= constructors(opt, ElementFilter.constructorsIn(c.getEnclosedElements()));
		    if (opt.showOperations)
			printedLines |= operations(opt, ElementFilter.methodsIn(c.getEnclosedElements()));

		    if (!printedLines)
			// if there are no operations nor constructors,
			// print an empty line to generate proper HTML
			tableLine(Align.LEFT, "");

		    innerTableEnd();
		}
	    }
	    externalTableEnd();
	    w.print(", URL=\"" + classToUrl(c, rootClass) + "\"");
	    nodeProperties(opt);

	    // If needed, add a note for this node
	    int ni = 0;
	    for(List<? extends DocTree> notes : findTags(root, c, "note").collect(Collectors.toList())) {
		String noteName = "n" + ni + "c" + ci.name;
		w.print("\t// Note annotation\n");
		w.print("\t" + noteName + " [label=");
		externalTableStart(UmlGraph.getCommentOptions(), c.getQualifiedName(), classToUrl(c, rootClass));
		innerTableStart();
		tableLine(Align.LEFT, Font.CLASS.wrap(UmlGraph.getCommentOptions(), htmlNewline(escape(flatText(notes)))));
		innerTableEnd();
		externalTableEnd();
		nodeProperties(UmlGraph.getCommentOptions());
		w.print("\t" + noteName + " -> " + relationNode(c) + "[arrowhead=none];\n");
		ni++;
	    }
	    ci.nodePrinted = true;
	}
	return ci.name;
    }

    private String getNodeName(TypeElement c) {
	String className = c.toString();
	ClassInfo ci = getClassInfo(className);
	return ci != null ? ci.name : newClassInfo(className, false, hidden(c)).name;
    }
    
    /** Return a class's internal name */
    private String getNodeName(String c) {
	ClassInfo ci = getClassInfo(c);
	return ci != null ? ci.name : newClassInfo(c, false, false).name;
    }

    /**
     * Print all relations for a given's class's tag
     * @param tagname the tag containing the given relation
     * @param from the source class
     * @param edgetype the dot edge specification
     */
    private void allRelation(Options opt, RelationType rt, TypeElement from) {
	String tagname = rt.lower;
	findTags(root, from, tagname).forEach(tag -> {
	    final String text = flatText(tag);
	    String t[] = tokenize(text);    // l-src label l-dst target
	    if (t.length != 4) {
		System.err.println("Error in " + from + "\n" + tagname + " expects four fields (l-src label l-dst target): " + text);
		return;
	    }
	    TypeElement to = root.getElementUtils().getTypeElement(t[3]);
	    if (to != null) {
		if(hidden(to))
		    return;
		relation(opt, rt, from, to, t[0], t[1], t[2]);
	    } else {
		if(hidden(t[3]))
		    return;
		relation(opt, rt, from, from.toString(), to, t[3], t[0], t[1], t[2]);
	    }
	});
    }

    /**
     * Print the specified relation
     * @param from the source class (may be null)
     * @param fromName the source class's name
     * @param to the destination class (may be null)
     * @param toName the destination class's name
     */
    private void relation(Options opt, RelationType rt, TypeElement from, String fromName, 
	    TypeElement to, String toName, String tailLabel, String label, String headLabel) {

	// print relation
	w.println("\t// " + fromName + " " + rt.toString() + " " + toName);
	w.println("\t" + relationNode(from, fromName) + " -> " + relationNode(to, toName) + " [" +
    	"taillabel=\"" + tailLabel + "\", " +
    	((label == null || label.isEmpty()) ? "label=\"\", " : "label=\"" + guillemize(opt, label) + "\", ") +
    	"headlabel=\"" + headLabel + "\", " +
    	"fontname=\"" + opt.edgeFontName + "\", " +
    	"fontcolor=\"" + opt.edgeFontColor + "\", " +
    	"fontsize=" + opt.edgeFontSize + ", " +
    	"color=\"" + opt.edgeColor + "\", " +
    	rt.style + "];"
    	);
	
	// update relation info
	RelationDirection d = RelationDirection.BOTH;
	if(rt == RelationType.NAVASSOC || rt == RelationType.DEPEND)
	    d = RelationDirection.OUT;
	getClassInfo(fromName).addRelation(toName, rt, d);
        getClassInfo(toName).addRelation(fromName, rt, d.inverse());
    }

    /**
     * Print the specified relation
     * @param from the source class
     * @param to the destination class
     */
    private void relation(Options opt, RelationType rt, TypeElement from,
	    TypeElement to, String tailLabel, String label, String headLabel) {
	relation(opt, rt, from, from.toString(), to, to.toString(), tailLabel, label, headLabel);
    }


    /** Return the full name of a relation's node.
     * This may involve appending the port :p for the standard nodes
     * whose outline is rendered through an inner table.
     */
    private String relationNode(TypeElement c) {
	return getNodeName(c) + optionProvider.getOptionsFor(root, c).shape.landingPort();
    }

    /** Return the full name of a relation's node c.
     * This may involve appending the port :p for the standard nodes
     * whose outline is rendered through an inner table.
     * @param c the node's class (may be null)
     * @param cName the node's class name
     */
    private String relationNode(TypeElement c, String cName) {
	Options opt = c == null ? optionProvider.getOptionsFor(cName) : optionProvider.getOptionsFor(root, c);
	return getNodeName(cName) + opt.shape.landingPort();
    }

    /** Print a class's relations */
    public void printRelations(TypeElement c) {
	Options opt = optionProvider.getOptionsFor(root, c);
	if (hidden(c) || c.getSimpleName().length() == 0) // avoid phantom classes, they may pop up when the source uses annotations
	    return;
	String className = c.toString();

	// Print generalization (through the Java superclass)
	Element s = c.getEnclosingElement();
	if (s != null &&
	    c.getKind() != ElementKind.ENUM &&
	    !s.toString().equals("java.lang.Object") &&
	    !hidden(s)) {
	    	TypeElement sc = (TypeElement) s;
		w.println("\t//" + c + " extends " + s + "\n" +
		    "\t" + relationNode(sc) + " -> " + relationNode(c) +
		    " [" + RelationType.EXTENDS.style + "];");
		getClassInfo(className).addRelation(sc.toString(), RelationType.EXTENDS, RelationDirection.OUT);
		getClassInfo(sc.toString()).addRelation(className, RelationType.EXTENDS, RelationDirection.IN);
	}

	// Print generalizations (through @extends tags)
	flatFindTags(root, c, "extends").filter(e -> e.getKind() == DocTree.Kind.TEXT) //
		.map(TextTree.class::cast).forEach(tag -> {
	    final String body = tag.getBody();
	    if (!hidden(body)) {
		TypeElement from = root.getElementUtils().getTypeElement(body);
		w.println("\t//" + c + " extends " + body + "\n" +
		    "\t" + relationNode(from, body) + " -> " + relationNode(c) + " [" + RelationType.EXTENDS.style + "];");
		getClassInfo(className).addRelation(body, RelationType.EXTENDS, RelationDirection.OUT);
		getClassInfo(body).addRelation(className, RelationType.EXTENDS, RelationDirection.IN);
	    }
	});
	// Print realizations (Java interfaces)
	for (TypeMirror iface : c.getInterfaces()) {
	    TypeElement ic = (TypeElement) root.getTypeUtils().asElement(iface);
	    if (!hidden(ic)) {
		w.println("\t//" + c + " implements " + ic + "\n\t" + relationNode(ic) + " -> " + relationNode(c)
			+ " [" + RelationType.IMPLEMENTS.style + "];");
		getClassInfo(className).addRelation(ic.toString(), RelationType.IMPLEMENTS, RelationDirection.OUT);
		getClassInfo(ic.toString()).addRelation(className, RelationType.IMPLEMENTS, RelationDirection.IN);
	    }
	}
	// Print other associations
	allRelation(opt, RelationType.ASSOC, c);
	allRelation(opt, RelationType.NAVASSOC, c);
	allRelation(opt, RelationType.HAS, c);
	allRelation(opt, RelationType.NAVHAS, c);
	allRelation(opt, RelationType.COMPOSED, c);
	allRelation(opt, RelationType.NAVCOMPOSED, c);
	allRelation(opt, RelationType.DEPEND, c);
    }

    /** Print classes that were parts of relationships, but not parsed by javadoc */
    public void printExtraClasses(DocletEnvironment root) {
	Set<String> names = new HashSet<String>(classnames.keySet()); 
	for(String className: names) {
	    ClassInfo info = getClassInfo(className);
	    if (!info.nodePrinted) {
		TypeElement c = root.getElementUtils().getTypeElement(className);
		if(c != null) {
		    printClass(c, false);
		} else {
		    Options opt = optionProvider.getOptionsFor(className);
		    if(opt.matchesHideExpression(className))
			continue;
		    w.println("\t// " + className);
		    w.print("\t" + info.name + "[label=");
		    externalTableStart(opt, className, classToUrl(className));
		    innerTableStart();
		    String qualifiedName = qualifiedName(opt, className);
		    int startTemplate = qualifiedName.indexOf('<');
		    int idx = qualifiedName.lastIndexOf('.', startTemplate < 0 ? qualifiedName.length() - 1 : startTemplate);
		    if(opt.postfixPackage && idx > 0 && idx < (qualifiedName.length() - 1)) {
			String packageName = qualifiedName.substring(0, idx);
			String cn = qualifiedName.substring(idx + 1);
			tableLine(Align.CENTER, Font.CLASS.wrap(opt, escape(cn)));
			tableLine(Align.CENTER, Font.PACKAGE.wrap(opt, packageName));
		    } else {
			tableLine(Align.CENTER, Font.CLASS.wrap(opt, escape(qualifiedName)));
		    }
		    innerTableEnd();
		    externalTableEnd();
		    if (className == null || className.length() == 0)
			w.print(", URL=\"" + classToUrl(className) + "\"");
		    nodeProperties(opt);
		}
	    }
	}
    }
    
    /**
     * Prints associations recovered from the fields of a class. An association is inferred only
     * if another relation between the two classes is not already in the graph.
     * @param classes
     */    
    public void printInferredRelations(Collection<TypeElement> classes) {
        for (TypeElement c : classes)
            printInferredRelations(c);
    }
    
    /**
     * Prints associations recovered from the fields of a class. An association is inferred only
     * if another relation between the two classes is not already in the graph.
     * @param classes
     */  
    public void printInferredRelations(TypeElement c) {
	Options opt = optionProvider.getOptionsFor(root, c);

	// check if the source is excluded from inference
	if (hidden(c))
	    return;

	for (VariableElement field : ElementFilter.fieldsIn(c.getEnclosedElements())) {
	    if(hidden(field))
		continue;

	    // skip statics
	    if(field.getModifiers().contains(Modifier.STATIC))
		continue;
	    
	    // skip primitives
	    FieldRelationInfo fri = getFieldRelationInfo(field);
	    if (fri == null)
		continue;

	    // check if the destination is excluded from inference
	    if (hidden(fri.cd))
		continue;

	    // if source and dest are not already linked, add a dependency
	    RelationPattern rp = getClassInfo(c.getQualifiedName().toString()).getRelation(fri.cd.getQualifiedName().toString());
	    if (rp == null)
		relation(opt, opt.inferRelationshipType, c, fri.cd, "", "", fri.multiple ? "*" : "");
	}
    }

    /**
     * Prints dependencies recovered from the methods of a class. A
     * dependency is inferred only if another relation between the two
     * classes is not already in the graph.
     * @param classes
     */    
    public void printInferredDependencies(Collection<TypeElement> classes) {
	for (TypeElement c : classes)
	    printInferredDependencies(c);
    }

    /**
     * Prints dependencies recovered from the methods of a class. A
     * dependency is inferred only if another relation between the two
     * classes is not already in the graph.
     * @param classes
     */  
    public void printInferredDependencies(TypeElement c) {
	Options opt = optionProvider.getOptionsFor(root, c);

	String sourceName = c.toString();
	if (hidden(c))
	    return;

	Set<TypeMirror> types = new HashSet<TypeMirror>();
	// harvest method return and parameter types
	for (ExecutableElement method : filterByVisibility(ElementFilter.methodsIn(c.getEnclosedElements()), opt.inferDependencyVisibility)) {
	    types.add(method.getReturnType());
	    for (VariableElement parameter : method.getParameters())
		types.add(parameter.asType());
	}
	// and the field types
	if (!opt.inferRelationships)
	    for (VariableElement field : filterByVisibility(ElementFilter.fieldsIn(c.getEnclosedElements()), opt.inferDependencyVisibility))
		types.add(field.asType());
	// see if there are some type parameters
	if (c.getKind() == ElementKind.CLASS || c.getKind() == ElementKind.INTERFACE)
	    types.addAll(((DeclaredType) c).getTypeArguments());
	// see if type parameters extend something
	for(TypeParameterElement tv : c.getTypeParameters())
	    types.addAll(tv.getBounds());

	// compute dependencies
	for (TypeMirror type : types) {
	    // skip primitives and type variables, as well as dependencies
	    // on the source class
	    if (type.getKind().isPrimitive() || type instanceof WildcardType || type instanceof TypeVariable
		    || c.toString().equals(type.toString()))
		continue;

	    // check if the destination is excluded from inference
	    TypeElement fc = (TypeElement) root.getTypeUtils().asElement(type);
	    if (hidden(fc))
		continue;
	    
	    // check if source and destination are in the same package and if we are allowed
	    // to infer dependencies between classes in the same package
	    if(!opt.inferDepInPackage && c.getEnclosingElement().equals(fc.getEnclosingElement()))
		continue;

	    // if source and dest are not already linked, add a dependency
	    RelationPattern rp = getClassInfo(sourceName).getRelation(fc.toString());
	    if (rp == null || rp.matchesOne(new RelationPattern(RelationDirection.OUT))) {
		relation(opt, RelationType.DEPEND, c, fc, "", "", "");
	    }
	    
	}
    }
    
    /**
     * Returns all program element docs that have a visibility greater or
     * equal than the specified level
     */
    private <T extends Element> List<T> filterByVisibility(List<T> docs, Visibility visibility) {
	if (visibility == Visibility.PRIVATE)
	    return docs;

	List<T> filtered = new ArrayList<T>();
	for (T doc : docs)
	    if (Visibility.get(doc).compareTo(visibility) > 0)
		filtered.add(doc);
	return filtered;
    }



    private FieldRelationInfo getFieldRelationInfo(VariableElement field) {
	TypeMirror type = field.asType();
	if(type.getKind().isPrimitive() || type instanceof WildcardType || type instanceof TypeVariable)
	    return null;
	
	final TypeElement typeelem = (TypeElement) root.getTypeUtils().asElement(type);
	if (type.getKind() == TypeKind.ARRAY)
	    return new FieldRelationInfo(typeelem, true);
	
	// FIXME: re-add back support for detecting collections here
	return new FieldRelationInfo(typeelem, false);
    }

    /** Convert the class name into a corresponding URL */
    public String classToUrl(TypeElement cd, boolean rootClass) {
	// building relative path for context and package diagrams
	if(contextDoc != null && rootClass) {
	    // determine the context path, relative to the root
	    String packageName;
	    if (contextDoc instanceof TypeElement) {
		    packageName = root.getElementUtils().getPackageOf((TypeElement) contextDoc).toString();
		} else if (contextDoc instanceof PackageElement) {
		    packageName = ((PackageElement) contextDoc).getQualifiedName().toString();
		} else {
		    return classToUrl(cd.getQualifiedName().toString());
		}
	    return buildRelativePath(packageName, root.getElementUtils().getPackageOf(cd).toString()) + cd.getSimpleName() + ".html";
	} else {
	    return classToUrl(cd.getQualifiedName().toString());
	} 
    }

    protected static String buildRelativePath(String contextPackageName, String classPackageName) {
	// path, relative to the root, of the destination class
	String[] contextClassPath = contextPackageName.split("\\.");
	String[] currClassPath = classPackageName.split("\\.");

	// compute relative path between the context and the destination
	// ... first, compute common part
	int i = 0;
	while (i < contextClassPath.length && i < currClassPath.length
		&& contextClassPath[i].equals(currClassPath[i]))
	    i++;
	// ... go up with ".." to reach the common root
	StringBuilder buf = new StringBuilder();
	if (i == contextClassPath.length) {
	    buf.append('.').append(FILE_SEPARATOR);
	} else {
	    for (int j = i; j < contextClassPath.length; j++) {
		buf.append("..").append(FILE_SEPARATOR);
	    }
	}
	// ... go down from the common root to the destination
	for (int j = i; j < currClassPath.length; j++) {
	    buf.append(currClassPath[j]).append(FILE_SEPARATOR);
	}
	return buf.toString();
    }

    /** Convert the class name into a corresponding URL */
    public String classToUrl(String className) {
	String docRoot = mapApiDocRoot(className);
	if (docRoot == null)
	    return null;
	final TypeElement c = this.rootClassdocs.get(className);
	String pkgname, simplename;
	if (c == null) {
		int idx = className.lastIndexOf('.');
		pkgname = idx > 0 ? className.substring(0, idx) : "";
		simplename = idx > 0 ? className.substring(idx + 1) : className;
	} else {
	    pkgname = root.getElementUtils().getPackageOf(c).toString();
	    simplename = c.getSimpleName().toString();
	}
	return new StringBuilder(docRoot) //
		.append(pkgname.replace('.', FILE_SEPARATOR)) //
		.append(FILE_SEPARATOR) //
		.append(simplename).append(".html").toString();
    }

    /**
     * Returns the appropriate URL "root" for a given class name.
     * The root will be used as the prefix of the URL used to link the class in
     * the final diagram to the associated JavaDoc page.
     */
    private String mapApiDocRoot(String className) {
	/* If no packages are specified, we use apiDocRoot for all of them. */
	if (rootClasses.contains(className))
	    return optionProvider.getGlobalOptions().apiDocRoot;
	else
	    return optionProvider.getGlobalOptions().getApiDocRoot(className);
    }

    
    /** Dot prologue 
     * @throws IOException */
    public void prologue() throws IOException {
	Options opt = optionProvider.getGlobalOptions();
	OutputStream os;

	if (opt.outputFileName.equals("-"))
	    os = System.out;
	else {
	    // prepare output file. Use the output file name as a full path unless the output
	    // directory is specified
	    File file = new File(opt.outputDirectory, opt.outputFileName);
	    // make sure the output directory are there, otherwise create them
	    if (file.getParentFile() != null
		&& !file.getParentFile().exists())
		file.getParentFile().mkdirs();
	    os = new FileOutputStream(file);
	}

	// print prologue
	w = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(os), opt.outputEncoding));
	w.println(
	    "#!/usr/local/bin/dot\n" +
	    "#\n" +
	    "# Class diagram \n" +
	    "# Generated by UMLGraph version " +
	    Version.VERSION + " (http://www.spinellis.gr/umlgraph/)\n" +
	    "#\n\n" +
	    "digraph G {\n" +
	    "\tedge [fontname=\"" + opt.edgeFontName +
	    "\",fontsize=10,labelfontname=\"" + opt.edgeFontName +
	    "\",labelfontsize=10];\n" +
	    "\tnode [fontname=\"" + opt.nodeFontName +
	    "\",fontsize=10,shape=plaintext];"
	);

	w.println("\tnodesep=" + opt.nodeSep + ";");
	w.println("\tranksep=" + opt.rankSep + ";");
	if (opt.horizontal)
	    w.println("\trankdir=LR;");
	if (opt.bgColor != null)
	    w.println("\tbgcolor=\"" + opt.bgColor + "\";\n");
    }

    /** Dot epilogue */
    public void epilogue() {
	w.println("}\n");
    }
    
    @Override
    public void close() {
	w.flush();
	w.close();
    }
    
    private void externalTableStart(Options opt, CharSequence name, String url) {
	String bgcolor = opt.nodeFillColor == null ? "" : (" bgcolor=\"" + opt.nodeFillColor + "\"");
	String href = url == null ? "" : (" href=\"" + url + "\" target=\"_parent\"");
	w.print("<<table title=\"" + name + "\" border=\"0\" cellborder=\"" + 
	    opt.shape.cellBorder() + "\" cellspacing=\"0\" " +
	    "cellpadding=\"2\" port=\"p\"" + bgcolor + href + ">" + linePostfix);
    }
    
    private void externalTableEnd() {
	w.print(linePrefix + linePrefix + "</table>>");
    }
    
    private void innerTableStart() {
	w.print(linePrefix + linePrefix + "<tr><td><table border=\"0\" cellspacing=\"0\" "
		+ "cellpadding=\"1\">" + linePostfix);
    }
    
    /**
     * Start the first inner table of a class.
     * @param nRows the total number of rows in this table.
     */
    private void firstInnerTableStart(Options opt, int nRows) {
	w.print(linePrefix + linePrefix + "<tr>" + opt.shape.extraColumn(nRows) +
		"<td><table border=\"0\" cellspacing=\"0\" " +
		"cellpadding=\"1\">" + linePostfix);
    }
    
    private void innerTableEnd() {
	w.print(linePrefix + linePrefix + "</table></td></tr>" + linePostfix);
    }

    /**
     * End the first inner table of a class.
     * @param nRows the total number of rows in this table.
     */
    private void firstInnerTableEnd(Options opt, int nRows) {
	w.print(linePrefix + linePrefix + "</table></td>" +
	    opt.shape.extraColumn(nRows) + "</tr>" + linePostfix);
    }

    private void tableLine(Align align, CharSequence text) {
	w.print("<tr><td align=\"" + align.lower + "\" balign=\"" + align.lower + "\">" //
		+ text // MAY contain markup!
		+ "</td></tr>" + linePostfix);
    }

    private static class FieldRelationInfo {
	TypeElement cd;
	boolean multiple;

	public FieldRelationInfo(TypeElement cd, boolean multiple) {
	    this.cd = cd;
	    this.multiple = multiple;
	}
    }
}
