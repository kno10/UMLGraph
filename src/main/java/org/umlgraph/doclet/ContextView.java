package org.umlgraph.doclet;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.lang.model.element.TypeElement;

import jdk.javadoc.doclet.DocletEnvironment;

/**
 * A view designed for UMLDoc, filters out everything that it's not directly
 * connected to the center class of the context.
 * <p>
 * As such, can be viewed as a simplified version of a {@linkplain View} using a
 * single {@linkplain ContextMatcher}, but provides some extra configuration
 * such as context highlighting and output path configuration (and it is
 * specified in code rather than in javadoc comments).
 * @author wolf
 * 
 */
public class ContextView implements OptionProvider {

    private TypeElement cd;
    private ContextMatcher matcher;
    private Options globalOptions;
    private Options myGlobalOptions;
    private Options hideOptions;
    private Options centerOptions;
    private Options packageOptions;
    private static final String[] HIDE_OPTIONS = new String[] { "hide" };

    public ContextView(String outputFolder, TypeElement cd, DocletEnvironment root, Options parent)
	    throws IOException {
	this.cd = cd;
	// FIXME: does this really work with inner classes?
	String outputPath = root.getElementUtils().getPackageOf(cd).toString().replace('.', '/') + "/" + cd.getSimpleName()
		+ ".dot";

	// setup options statically, so that we won't need to change them so
	// often
	this.globalOptions = parent.getGlobalOptions();
	
	this.packageOptions = parent.getGlobalOptions();  
	this.packageOptions.showQualified = false;

	this.myGlobalOptions = parent.getGlobalOptions();
	this.myGlobalOptions.setOption(new String[] { "output", outputPath });
	this.myGlobalOptions.setOption(HIDE_OPTIONS);

	this.hideOptions = parent.getGlobalOptions();
	this.hideOptions.setOption(HIDE_OPTIONS);

	this.centerOptions = parent.getGlobalOptions();
	this.centerOptions.nodeFillColor = "lemonChiffon";
	this.centerOptions.showQualified = false;

	this.matcher = new ContextMatcher(root, Pattern.compile(Pattern.quote(cd.getQualifiedName().toString())),
		myGlobalOptions, true);

    }

    public void setContextCenter(DocletEnvironment root, TypeElement contextCenter) {
	this.cd = contextCenter;
	// FIXME: does this really work with inner classes? Probably not.
	String outputPath = root.getElementUtils().getPackageOf(cd).toString().replace('.', '/') + "/" + cd.getSimpleName() + ".dot";
	this.myGlobalOptions.setOption(new String[] { "output", outputPath });
	matcher.setContextCenter(Pattern.compile(cd.toString()));
    }

    public String getDisplayName() {
	return "Context view for class " + cd;
    }

    public Options getGlobalOptions() {
	return myGlobalOptions;
    }

    public Options getOptionsFor(DocletEnvironment root, TypeElement cd) {
	Options opt;
	if (globalOptions.matchesHideExpression(cd.getQualifiedName())
		|| !(matcher.matches(cd) || globalOptions.matchesIncludeExpression(cd.getQualifiedName()))) {
		opt = hideOptions;
	} else if (cd.equals(this.cd)) {
		opt = centerOptions;
	} else if(cd.getEnclosingElement().equals(this.cd.getEnclosingElement())){
		opt = packageOptions;
	} else {
		opt = globalOptions;
	}
	Options optionClone = (Options) opt.clone();
	overrideForClass(optionClone, root, cd);
	return optionClone;
    }

    public Options getOptionsFor(String name) {
	Options opt;
	if (!matcher.matches(name))
		opt = hideOptions;
	else if (name.contentEquals(cd.getQualifiedName()))
		opt = centerOptions;
	else
		opt = globalOptions;
	Options optionClone = (Options) opt.clone();
	overrideForClass(optionClone, name);
	return optionClone;
    }

    public void overrideForClass(Options opt, DocletEnvironment root, TypeElement cd) {
	opt.setOptions(root, cd);
	if (opt.matchesHideExpression(cd.getQualifiedName())
		|| !(matcher.matches(cd) || opt.matchesIncludeExpression(cd.getQualifiedName())))
	    opt.setOption(HIDE_OPTIONS);
	if (cd.equals(this.cd))
	    opt.nodeFillColor = "lemonChiffon";
    }

    public void overrideForClass(Options opt, String className) {
	if (!(matcher.matches(className) || opt.matchesIncludeExpression(className)))
	    opt.setOption(HIDE_OPTIONS);
    }

}
