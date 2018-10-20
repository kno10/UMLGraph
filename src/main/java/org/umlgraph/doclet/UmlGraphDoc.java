package org.umlgraph.doclet;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.TreeSet;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Pattern;

import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic.Kind;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;
import jdk.javadoc.doclet.StandardDoclet;

import static org.umlgraph.doclet.DocletUtil.*;

/**
 * Chaining doclet that runs the standart Javadoc doclet first, and on success,
 * runs the generation of dot files by UMLGraph
 * @author wolf
 */
public class UmlGraphDoc extends StandardDoclet {
    private Reporter reporter;
    
    private UmlGraph umlgraph = new UmlGraph();

    @Override
    public Set<Option> getSupportedOptions() {
        Set<Option> opts = new HashSet<Option>(super.getSupportedOptions());
        opts.addAll(umlgraph.getSupportedOptions());
        return opts;
    }

    @Override
    public void init(Locale locale, Reporter reporter) {
        super.init(locale, reporter);
	this.reporter = reporter;
    }
    
    @Override
    public boolean run(DocletEnvironment root) {
	reporter.print(Kind.NOTE, "UmlGraphDoc version " + Version.VERSION +  ", running the standard doclet");
        if (!super.run(root)) {
            return false;
        }
        reporter.print(Kind.NOTE,  "UmlGraphDoc version " + Version.VERSION + ", altering javadocs");
	try {
	    String outputFolder = findOutputPath(root.options());

	    Options opt = umlgraph.buildOptions(root);
	    opt.setOptions(root.options());
	    // in javadoc enumerations are always printed
	    opt.showEnumerations = true;
	    opt.relativeLinksForSourcePackages = true;
	    // enable strict matching for hide expressions
	    opt.strictMatching = true;
//	    reporter.print(Kind.NOTE,  opt.toString());

	    generatePackageDiagrams(root, opt, outputFolder);
	    generateContextDiagrams(root, opt, outputFolder);
	} catch(Throwable t) {
	    reporter.print(Kind.ERROR,  "Error: " + t.toString());
	    t.printStackTrace();
	    return false;
	}
    }
    
    /**
     * Generates the package diagrams for all of the packages that contain classes among those 
     * returned by DocletEnvironment.class() 
     */
    private void generatePackageDiagrams(DocletEnvironment root, Options opt, String outputFolder)
	    throws IOException {
	Set<String> packages = new HashSet<String>();
	for (TypeElement classDoc : ElementFilter.typesIn(root.getIncludedElements())) {
	    PackageElement PackageElement = root.getElementUtils().getPackageOf(classDoc);
	    String qname = PackageElement.getQualifiedName().toString();
	    if(!packages.contains(qname)) {
		packages.add(qname);
    	    OptionProvider view = new PackageView(outputFolder, PackageElement, root, opt);
    	    umlgraph.buildGraph(root, view, PackageElement);
    	    runGraphviz(opt.dotExecutable, outputFolder, qname, qname, root);
    	    alterHtmlDocs(opt, outputFolder, qname, qname,
    		    "package-summary.html", Pattern.compile("(</[Hh]2>)|(<h1 title=\"Package\").*"), root);
	    }
	}
    }

    /**
     * Generates the context diagram for a single class
     */
    private void generateContextDiagrams(DocletEnvironment root, Options opt, String outputFolder)
	    throws IOException {
        Set<TypeElement> classDocs = new TreeSet<TypeElement>(new Comparator<TypeElement>() {
            public int compare(TypeElement cd1, TypeElement cd2) {
                return cd1.getQualifiedName().toString().compareTo(cd2.getQualifiedName().toString());
            }
        });
        for (TypeElement classDoc : ElementFilter.typesIn(root.getIncludedElements()))
            classDocs.add(classDoc);

	ContextView view = null;
	for (TypeElement classDoc : classDocs) {
	    if(view == null)
		view = new ContextView(outputFolder, classDoc, root, opt);
	    else
		view.setContextCenter(root, classDoc);
	    umlgraph.buildGraph(root, view, classDoc);
	    runGraphviz(opt.dotExecutable, outputFolder, root.getElementUtils().getPackageOf(classDoc).toString(), classDoc.getQualifiedName().toString(), root);
	    alterHtmlDocs(opt, outputFolder, root.getElementUtils().getPackageOf(classDoc).toString(), classDoc.getQualifiedName().toString(),
		    classDoc.getQualifiedName() + ".html", Pattern.compile(".*(Class|Interface|Enum) " + classDoc.getSimpleName() + ".*") , root);
	}
    }

    /**
     * Runs Graphviz dot building both a diagram (in png format) and a client side map for it.
     */
    private void runGraphviz(String dotExecutable, String outputFolder, String packageName, String name, DocletEnvironment root) {
    if (dotExecutable == null) {
      dotExecutable = "dot";
    }
	File dotFile = new File(outputFolder, packageName.replace(".", "/") + "/" + name + ".dot");
  File svgFile = new File(outputFolder, packageName.replace(".", "/") + "/" + name + ".svg");

	try {
	    Process p = Runtime.getRuntime().exec(new String [] {
		dotExecutable,
    "-Tsvg",
		"-o",
		svgFile.getAbsolutePath(),
		dotFile.getAbsolutePath()
	    });
	    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
	    String line;
	    while((line = reader.readLine()) != null)
		reporter.print(Kind.WARNING, line);
	    int result = p.waitFor();
	    if (result != 0)
		reporter.print(Kind.WARNING,  "Errors running Graphviz on " + dotFile);
	} catch (Exception e) {
	    e.printStackTrace();
	    System.err.println("Ensure that dot is in your path and that its path does not contain spaces");
	}
    }

    //Format string for the uml image div tag.
    private static final String UML_DIV_TAG = 
	"<div align=\"center\">" +
	    "<object width=\"100%%\" height=\"100%%\" type=\"image/svg+xml\" data=\"%1$s.svg\" alt=\"Package class diagram package %1$s\" border=0></object>" +
	"</div>";
    
    private static final String UML_AUTO_SIZED_DIV_TAG = 
    "<div align=\"center\">" +
        "<object type=\"image/svg+xml\" data=\"%1$s.svg\" alt=\"Package class diagram package %1$s\" border=0></object>" +
    "</div>";
    
    private static final String EXPANDABLE_UML_STYLE = "font-family: Arial,Helvetica,sans-serif;font-size: 1.5em; display: block; width: 250px; height: 20px; background: #009933; padding: 5px; text-align: center; border-radius: 8px; color: white; font-weight: bold;";

    //Format string for the java script tag.
    private static final String EXPANDABLE_UML = 
	"<script type=\"text/javascript\">\n" + 
	"function show() {\n" + 
	"    document.getElementById(\"uml\").innerHTML = \n" + 
	"        \'<a style=\"" + EXPANDABLE_UML_STYLE + "\" href=\"javascript:hide()\">%3$s</a>\' +\n" +
	"        \'%1$s\';\n" + 
	"}\n" + 
	"function hide() {\n" + 
	"	document.getElementById(\"uml\").innerHTML = \n" + 
	"	\'<a style=\"" + EXPANDABLE_UML_STYLE + "\" href=\"javascript:show()\">%2$s</a>\' ;\n" +
	"}\n" + 
	"</script>\n" + 
	"<div id=\"uml\" >\n" + 
	"	<a href=\"javascript:show()\">\n" + 
	"	<a style=\"" + EXPANDABLE_UML_STYLE + "\" href=\"javascript:show()\">%2$s</a> \n" +
	"</div>";
    
    /**
     * Takes an HTML file, looks for the first instance of the specified insertion point, and
     * inserts the diagram image reference and a client side map in that point.
     */
    private void alterHtmlDocs(Options opt, String outputFolder, String packageName, String className,
	    String htmlFileName, Pattern insertPointPattern, DocletEnvironment root) throws IOException {
	// setup files
	File output = new File(outputFolder, packageName.replace(".", "/"));
	File htmlFile = new File(output, htmlFileName);
	File alteredFile = new File(htmlFile.getAbsolutePath() + ".uml");
	if (!htmlFile.exists()) {
	    System.err.println("Expected file not found: " + htmlFile.getAbsolutePath());
	    return;
	}

	// parse & rewrite
	BufferedWriter writer = null;
	BufferedReader reader = null;
	boolean matched = false;
	try {
	    writer = new BufferedWriter(new OutputStreamWriter(new
		    FileOutputStream(alteredFile), opt.outputEncoding));
	    reader = new BufferedReader(new InputStreamReader(new
		    FileInputStream(htmlFile), opt.outputEncoding));

	    String line;
	    while ((line = reader.readLine()) != null) {
		writer.write(line);
		writer.newLine();
		if (!matched && insertPointPattern.matcher(line).matches()) {
		    matched = true;
			
		    String tag;
		    if (opt.autoSize)
		        tag = String.format(UML_AUTO_SIZED_DIV_TAG, className);
		    else
                tag = String.format(UML_DIV_TAG, className);
		    if (opt.collapsibleDiagrams)
		    	tag = String.format(EXPANDABLE_UML, tag, "Show UML class diagram", "Hide UML class diagram");
		    writer.write("<!-- UML diagram added by UMLGraph version " +
		    		Version.VERSION + 
				" (http://www.spinellis.gr/umlgraph/) -->");
		    writer.newLine();
		    writer.write(tag);
		    writer.newLine();
		}
	    }
	} finally {
	    if (writer != null)
		writer.close();
	    if (reader != null)
		reader.close();
	}

	// if altered, delete old file and rename new one to the old file name
	if (matched) {
	    htmlFile.delete();
	    alteredFile.renameTo(htmlFile);
	} else {
	    reporter.print(Kind.NOTE,  "Warning, could not find a line that matches the pattern '" + insertPointPattern.pattern() 
		    + "'.\n Class diagram reference not inserted");
	    alteredFile.delete();
	}
    }

    /**
     * Returns the output path specified on the javadoc options
     */
    private static String findOutputPath(String[][] options) {
	for (int i = 0; i < options.length; i++) {
	    if (options[i][0].equals("-d"))
		return options[i][1];
	}
	return ".";
    }
}
