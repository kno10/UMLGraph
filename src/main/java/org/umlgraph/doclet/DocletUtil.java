package org.umlgraph.doclet;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import com.sun.source.util.SimpleDocTreeVisitor;

import jdk.javadoc.doclet.DocletEnvironment;

/**
 * Helper functions for Java 9+ doclets.
 * 
 * @author Erich Schubert
 */
public class DocletUtil {
    public static Stream<List<? extends DocTree>> findTags(DocCommentTree doctree, String filter) {
	// Ugly functional programming style. This could have been a nice simple for
	// loop... Welcome the new spaghetti code: functional programming
	// This would also be more logical if there was an elegant way to filter-and-map
	// at the same time (filter by kind, then cast to subclass).
	return doctree.getBlockTags().stream() //
		.filter(child -> child.getKind() == DocTree.Kind.UNKNOWN_BLOCK_TAG) //
		.map(UnknownBlockTagTree.class::cast) //
		.filter(child -> filter.equals(child.getTagName())) //
		.map(UnknownBlockTagTree::getContent);
    }

    public static Stream<List<? extends DocTree>> findTags(DocletEnvironment root, Element e, String filter) {
	return findTags(root.getDocTrees().getDocCommentTree(e), filter);
    }

    public static Stream<? extends DocTree> flatFindTags(DocletEnvironment root, Element e, String filter) {
	return findTags(root.getDocTrees().getDocCommentTree(e), filter).flatMap(List::stream);
    }

    public static String flatText(List<? extends DocTree> subtree) {
	if (subtree.isEmpty())
	    return "";
	if (subtree.size() == 1) {
	    DocTree first = subtree.get(0);
	    if (first.getKind() == DocTree.Kind.TEXT)
		return ((TextTree) first).getBody();
	}
	StringBuilder buf = new StringBuilder();
	subtree.forEach(t -> t.accept(TEXT_VISITOR, buf));
	return buf.toString();
    }

    /**
     * Collect the text contents of a doctree.
     */
    public static SimpleDocTreeVisitor<Void, StringBuilder> TEXT_VISITOR = new SimpleDocTreeVisitor<Void, StringBuilder>() {
	@Override
	public Void visitText(TextTree node, StringBuilder p) {
	    p.append(node.getBody());
	    return null;
	}
    };

    // Missing from ElementFilter
    public static List<VariableElement> enumConstantsIn(Iterable<? extends Element> elements) {
	List<VariableElement> list = new ArrayList<>();
	for (Element e : elements)
	    if (e.getKind() == ElementKind.ENUM_CONSTANT)
		list.add((VariableElement) e);
	return list;
    }
}