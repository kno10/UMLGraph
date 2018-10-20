package org.umlgraph.doclet;

import java.util.regex.Pattern;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import jdk.javadoc.doclet.DocletEnvironment;

/**
 * Matches every class that implements (directly or indirectly) an
 * interfaces matched by regular expression provided.
 */
public class InterfaceMatcher implements ClassMatcher {

    protected DocletEnvironment root;
    protected Pattern pattern;

    public InterfaceMatcher(DocletEnvironment root, Pattern pattern) {
	this.root = root;
	this.pattern = pattern;
    }

    public boolean matches(TypeElement cd) {
	// if it's the interface we're looking for, match
	if(cd.getKind() == ElementKind.INTERFACE && pattern.matcher(cd.toString()).matches())
	    return true;
	
	// for each interface, recurse, since classes and interfaces 
	// are treated the same in the doclet API
	for (TypeMirror iface : cd.getInterfaces())
	    if(matches((TypeElement)root.getTypeUtils().asElement(iface)))
		return true;
	
	// recurse on supeclass, if available
	return cd.getSuperclass() != null && matches((TypeElement)root.getTypeUtils().asElement(cd.getSuperclass()));
    }

    public boolean matches(CharSequence name) {
	TypeElement cd = root.getElementUtils().getTypeElement(name);
	return cd != null && matches(cd);
    }

}
