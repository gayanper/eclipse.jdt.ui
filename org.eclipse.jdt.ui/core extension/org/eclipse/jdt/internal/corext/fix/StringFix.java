/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.fix;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.TextEditGroup;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.ltk.core.refactoring.TextChange;

import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;

import org.eclipse.jdt.internal.corext.refactoring.changes.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChangeCompatibility;
import org.eclipse.jdt.internal.corext.refactoring.nls.NLSUtil;
import org.eclipse.jdt.internal.corext.util.Strings;

import org.eclipse.jdt.ui.text.java.IProblemLocation;

import org.eclipse.jdt.internal.ui.text.correction.ProblemLocation;

/**
 * Fix which solves various issues with strings.
 * Supported:
 * 		Add missing $NON-NLS$ tag
 * 		Remove unnecessary $NON-NLS$ tag
 *
 */
public class StringFix implements IFix {
	
	private final TextEditGroup[] fEditGroups;
	private final String fName;
	private final ICompilationUnit fCompilationUnit;

	public static StringFix createFix(CompilationUnit compilationUnit, IProblemLocation problem, boolean removeNLSTag, boolean addNLSTag) throws CoreException {
		TextEdit addEdit= null;
		ICompilationUnit cu= (ICompilationUnit)compilationUnit.getJavaElement();
		if (addNLSTag) {
			addEdit= NLSUtil.createNLSEdit(cu, problem.getOffset());
		}
		ReplaceEdit removeEdit= null;
		if (removeNLSTag) {
			IBuffer buffer= cu.getBuffer();
			if (buffer != null) {
				removeEdit= getReplace(problem.getOffset(), problem.getLength(), buffer, true);
			}
		}

		if (addEdit != null && removeEdit != null) {
			String label= FixMessages.StringFix_AddRemoveNonNls_description;
			return new StringFix(label, compilationUnit, new TextEditGroup[] {new TextEditGroup(label, addEdit), new TextEditGroup(label, removeEdit)});
		} else if (addEdit != null) {
			String label= FixMessages.StringFix_AddNonNls_description;
			return new StringFix(label, compilationUnit, new TextEditGroup[] {new TextEditGroup(label, addEdit)});
		} else if (removeEdit != null) {
			String label= FixMessages.StringFix_RemoveNonNls_description;
			return new StringFix(label, compilationUnit, new TextEditGroup[] {new TextEditGroup(label, removeEdit)});
		} else {
			return null;
		}
	}
	
	public static IFix createCleanUp(CompilationUnit compilationUnit, boolean addNLSTag, boolean removeNLSTag) throws CoreException, JavaModelException {
		if (!addNLSTag && !removeNLSTag)
			return null;
		
		IProblem[] problems= compilationUnit.getProblems();
		IProblemLocation[] locations= new IProblemLocation[problems.length];
		for (int i= 0; i < problems.length; i++) {
			locations[i]= new ProblemLocation(problems[i]);
		}
		return createCleanUp(compilationUnit, addNLSTag, removeNLSTag, locations);
	}
	
	public static IFix createCleanUp(CompilationUnit compilationUnit, IProblemLocation[] problems, boolean addNLSTag, boolean removeNLSTag) throws CoreException, JavaModelException {
		if (!addNLSTag && !removeNLSTag)
			return null;
		
		return createCleanUp(compilationUnit, addNLSTag, removeNLSTag, problems);
	}

	private static IFix createCleanUp(CompilationUnit compilationUnit, boolean addNLSTag, boolean removeNLSTag, IProblemLocation[] problems) throws CoreException, JavaModelException {
		ICompilationUnit cu= (ICompilationUnit)compilationUnit.getJavaElement();
		List result= new ArrayList();
		
		for (int i= 0; i < problems.length; i++) {
			IProblemLocation problem= problems[i];
			if (addNLSTag && problem.getProblemId() == IProblem.NonExternalizedStringLiteral) {
				TextEdit edit= NLSUtil.createNLSEdit(cu, problem.getOffset());
				if (edit != null) {
					result.add(new TextEditGroup(FixMessages.StringFix_AddNonNls_description, edit));
				}
			}
			if (removeNLSTag && problem.getProblemId() == IProblem.UnnecessaryNLSTag) {
				IBuffer buffer= cu.getBuffer();
				if (buffer != null) {
					TextEdit edit= StringFix.getReplace(problem.getOffset(), problem.getLength(), buffer, false);
					if (edit != null) {
						result.add(new TextEditGroup(FixMessages.StringFix_RemoveNonNls_description, edit));
					}
				}
			}
		}
		if (result.isEmpty())
			return null;
		
		return new StringFix("", compilationUnit, (TextEditGroup[])result.toArray(new TextEditGroup[result.size()])); //$NON-NLS-1$
	}
	
	private static ReplaceEdit getReplace(int offset, int length, IBuffer buffer, boolean removeLeadingIndents) {
		
		String replaceString= new String();
		boolean hasMoreInComment= false;
		
		// look after the tag
		int next= offset + length;
		while (next < buffer.getLength()) {
			char ch= buffer.getChar(next);
			if (Strings.isIndentChar(ch)) {
				next++; // remove all whitespace
			} else if (Strings.isLineDelimiterChar(ch)) {
				length= next - offset; 
				break;
			} else if (ch == '/') {
				next++;
				if (next == buffer.getLength() || buffer.getChar(next) != '/') {
					replaceString= "//"; //$NON-NLS-1$
				} else {
					length= next - offset - 1;
				}
				hasMoreInComment= true;
				break;
			} else {
				replaceString= "//"; //$NON-NLS-1$
				hasMoreInComment= true;
				break;
			}
		}
		if (!hasMoreInComment && removeLeadingIndents) {
			while (offset > 0 && Strings.isIndentChar(buffer.getChar(offset - 1))) {
				offset--;
				length++;
			}
		}
		if (length > 0) {
			ReplaceEdit replaceEdit= new ReplaceEdit(offset, length, replaceString);
			return replaceEdit;
		} else {
			return null;
		}
	}
	
	private StringFix(String name, CompilationUnit compilationUnit, TextEditGroup[] groups) {
		fName= name;
		fCompilationUnit= (ICompilationUnit)compilationUnit.getJavaElement();
		fEditGroups= groups;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.corext.fix.AbstractFix#createChange()
	 */
	public TextChange createChange() throws CoreException {
		if (fEditGroups == null || fEditGroups.length == 0)
			return null;
		
		CompilationUnitChange result= new CompilationUnitChange(getDescription(), getCompilationUnit());
		for (int i= 0; i < fEditGroups.length; i++) {
			TextEdit[] edits= fEditGroups[i].getTextEdits();
			String groupName= fEditGroups[i].getName();
			for (int j= 0; j < edits.length; j++) {
				TextChangeCompatibility.addTextEdit(result, groupName, edits[j]);	
			}
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.corext.fix.IFix#getDescription()
	 */
	public String getDescription() {
		return fName;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.corext.fix.IFix#getCompilationUnit()
	 */
	public ICompilationUnit getCompilationUnit() {
		return fCompilationUnit;
	}

}
