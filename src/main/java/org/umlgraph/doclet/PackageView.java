package org.umlgraph.doclet;

import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

import jdk.javadoc.doclet.DocletEnvironment;

/**
 * A view designed for UMLDoc, filters out everything that it's not contained in
 * the specified package.
 * <p>
 * As such, can be viewed as a simplified version of a {@linkplain View} using a
 * single {@linkplain ClassMatcher}, and provides some extra configuration such
 * as output path configuration (and it is specified in code rather than in
 * javadoc comments).
 * @author wolf
 * 
 */
public class PackageView implements OptionProvider {

    private static final String[] HIDE = new String[] { "hide" };
    private PackageElement pd;
    private OptionProvider parent;
    private ClassMatcher matcher;
    private String outputPath;
    private Options opt;

    public PackageView(String outputFolder, PackageElement pd, DocletEnvironment root, OptionProvider parent) {
	this.parent = parent;
	this.pd = pd;
	this.matcher = new PackageMatcher(pd);
	this.opt = parent.getGlobalOptions();
	this.opt.setOptions(root, pd);
	this.outputPath = pd.getQualifiedName().toString().replace('.', '/') + "/" + pd.getQualifiedName().toString() + ".dot";
    }

    public String getDisplayName() {
	return "Package view for package " + pd;
    }

    public Options getGlobalOptions() {
	Options go = parent.getGlobalOptions();

	go.setOption(new String[] { "output", outputPath });
	go.setOption(HIDE);

	return go;
    }

    public Options getOptionsFor(DocletEnvironment root, TypeElement cd) {
	Options go = parent.getGlobalOptions();
	overrideForClass(go, root, cd);
	return go;
    }

    public Options getOptionsFor(String name) {
	Options go = parent.getGlobalOptions();
	overrideForClass(go, name);
	return go;
    }

    public void overrideForClass(Options opt, DocletEnvironment root, TypeElement cd) {
	opt.setOptions(root, cd);
	boolean inPackage = matcher.matches(cd);
	if (inPackage)
	    opt.showQualified = false;
	if (!(inPackage || this.opt.matchesIncludeExpression(cd.getQualifiedName()))
		|| this.opt.matchesHideExpression(cd.getQualifiedName()))
	    opt.setOption(HIDE);
    }

    public void overrideForClass(Options opt, String className) {
	opt.showQualified = false;
	boolean inPackage = matcher.matches(className);
	if (inPackage)
	    opt.showQualified = false;
	if (!(inPackage || this.opt.matchesIncludeExpression(className))
		|| this.opt.matchesHideExpression(className))
	    opt.setOption(HIDE);
    }

}
