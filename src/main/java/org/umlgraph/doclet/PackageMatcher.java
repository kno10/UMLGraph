package org.umlgraph.doclet;

import javax.lang.model.element.*;

public class PackageMatcher implements ClassMatcher {
    protected PackageElement PackageElement;

    public PackageMatcher(PackageElement PackageElement) {
	super();
	this.PackageElement = PackageElement;
    }

    public boolean matches(TypeElement cd) {
	return cd.getEnclosingElement().equals(PackageElement);
    }

    public boolean matches(CharSequence name) {
	for (Element cd : PackageElement.getEnclosedElements())
	    if ((cd.getKind().isClass() || cd.getKind().isInterface())
		    && ((TypeElement) cd).getQualifiedName().contentEquals(name))
		return true;
	return false;
    }
}
