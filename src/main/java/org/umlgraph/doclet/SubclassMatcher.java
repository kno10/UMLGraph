package org.umlgraph.doclet;

import java.util.regex.Pattern;

import javax.lang.model.element.TypeElement;
import jdk.javadoc.doclet.DocletEnvironment;

/**
 * Matches every class that extends (directly or indirectly) a class
 * matched by the regular expression provided.
 */
public class SubclassMatcher implements ClassMatcher {

    protected DocletEnvironment root;
    protected Pattern pattern;

    public SubclassMatcher(DocletEnvironment root, Pattern pattern) {
	this.root = root;
	this.pattern = pattern;
    }

    public boolean matches(TypeElement cd) {
	// if it's the class we're looking for return
	if(pattern.matcher(cd.toString()).matches())
	    return true;
	
	// recurse on supeclass, if available
	return cd.getSuperclass() != null && matches((TypeElement) root.getTypeUtils().asElement(cd.getSuperclass()));
    }

    public boolean matches(CharSequence name) {
	TypeElement cd = root.getElementUtils().getTypeElement(name);
	return cd != null && matches(cd);
    }

}
