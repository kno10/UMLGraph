/*
 * Create a graphviz graph based on the classes in the specified java
 * source files.
 *
 * (C) Copyright 2002-2010 Diomidis Spinellis
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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

import jdk.javadoc.doclet.*;
import javax.tools.Diagnostic.Kind;
import jdk.javadoc.doclet.Doclet.Option;

/**
 * Doclet API implementation
 * @depend - - - OptionProvider
 * @depend - - - Options
 * @depend - - - View
 * @depend - - - ClassGraph
 * @depend - - - Version
 *
 * @version $Revision$
 * @author <a href="http://www.spinellis.gr">Diomidis Spinellis</a>
 */
public class UmlGraph implements Doclet {

    private static final String programName = "UmlGraph";
    private static final String docletName = "org.umlgraph.doclet.UmlGraph";

    /** Options used for commenting nodes */
    private static Options commentOptions;

    private Reporter reporter;
    
    @Override
    public String getName() {
        return docletName;
    }
    
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_5;
    }

    @Override
    public Set<Option> getSupportedOptions() {
        Set<Option> opts = new HashSet<Option>();
        // FIXME: add UmlGraph options.
        return opts;
    }

    @Override
    public void init(Locale locale, Reporter reporter) {
	// TODO: support locales?
	this.reporter = reporter;
    }    

    /** Entry point through javadoc */
    @Override
    public boolean run(DocletEnvironment root) {
	Options opt = buildOptions(root);
	reporter.print(Kind.NOTE, "UMLGraph doclet version " + Version.VERSION + " started");

	View[] views = buildViews(opt, root, root);
	if(views == null)
	    return false;
	if (views.length == 0)
	    buildGraph(root, opt, null);
	else
	    for (int i = 0; i < views.length; i++)
		buildGraph(root, views[i], null);
	return true;
    }

    public static Options getCommentOptions() {
    	return commentOptions;
    }

    /**
     * Creates the base Options object.
     * This contains both the options specified on the command
     * line and the ones specified in the UMLOptions class, if available.
     * Also create the globally accessible commentOptions object.
     */
    public static Options buildOptions(DocletEnvironment root) {
	commentOptions = new Options();
	commentOptions.setOptions(root.options());
	commentOptions.setOptions(root, root.getElementUtils().getTypeElement("UMLNoteOptions"));
	commentOptions.shape = Shape.NOTE;

	Options opt = new Options();
	opt.setOptions(root.options());
	opt.setOptions(root, root.getElementUtils().getTypeElement("UMLOptions"));
	return opt;
    }

    /**
     * Builds and outputs a single graph according to the view overrides
     */
    public void buildGraph(DocletEnvironment root, OptionProvider op, Element contextDoc) {
	if(getCommentOptions() == null)
	    buildOptions(root);
	Options opt = op.getGlobalOptions();
	reporter.print(Kind.NOTE, "Building " + op.getDisplayName());
	Set<TypeElement> classes = ElementFilter.typesIn(root.getIncludedElements());

	try (ClassGraph c = new ClassGraph(root, op, contextDoc)) {
	    c.prologue();
	    for(TypeElement e: classes)
		c.printClass(e, true);
	    for(TypeElement e: classes)
		c.printRelations(e);
	    if(opt.inferRelationships)
		c.printInferredRelations(classes);
	    if(opt.inferDependencies)
		c.printInferredDependencies(classes);

	    c.printExtraClasses(root);
	    c.epilogue();
	} catch (IOException ex) {
	    ex.printStackTrace();
	}
    }

    /**
     * Builds the views according to the parameters on the command line
     * @param opt The options
     * @param srcRootDoc The DocletEnvironment for the source classes
     * @param viewRootDoc The DocletEnvironment for the view classes (may be
     *                different, or may be the same as the srcRootDoc)
     */
    public View[] buildViews(Options opt, DocletEnvironment srcRootDoc, DocletEnvironment viewRootDoc) {
	if (opt.viewName != null) {
	    TypeElement viewClass = viewRootDoc.getElementUtils().getTypeElement(opt.viewName);
	    if(viewClass == null) {
		System.out.println("View " + opt.viewName + " not found! Exiting without generating any output.");
		return null;
	    }
	    if(DocletUtil.findTags(viewRootDoc, viewClass, "view").findFirst().isEmpty()) {
		System.out.println(viewClass + " is not a view!");
		return null;
	    }
	    if(viewClass.getModifiers().contains(Modifier.ABSTRACT)) {
		System.out.println(viewClass + " is an abstract view, no output will be generated!");
		return null;
	    }
	    return new View[] { buildView(srcRootDoc, viewClass, opt) };
	} else if (opt.findViews) {
	    List<View> views = new ArrayList<View>();
	    Set<? extends Element> classes = viewRootDoc.getIncludedElements();

	    // find view classes
	    for (Element e : classes)
		if (DocletUtil.findTags(viewRootDoc, e, "view").findFirst().isPresent() && !e.getModifiers().contains(Modifier.ABSTRACT))
		    views.add(buildView(srcRootDoc, (TypeElement) e, opt));

	    return views.toArray(new View[views.size()]);
	} else
	    return new View[0];
    }

    /**
     * Builds a view along with its parent views, recursively
     */
    private View buildView(DocletEnvironment root, TypeElement viewClass, OptionProvider provider) {
	TypeMirror superClass = viewClass.getSuperclass();
	if(superClass.getKind() == TypeKind.NONE || DocletUtil.findTags(root, root.getTypeUtils().asElement(superClass), "view").findFirst().isEmpty())
	    return new View(root, viewClass, provider);

	return new View(root, viewClass, buildView(root, (TypeElement) root.getTypeUtils().asElement(superClass), provider));
    }

    /** Option checking */
    public static int optionLength(String option) {
	return Options.optionLength(option);
    }
}
