/*******************************************************************************
 * Copyright (c) 2002 International Business Machines Corp. and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.code;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.codemanipulation.StubUtility;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.ASTRewrite;
import org.eclipse.jdt.internal.corext.dom.Binding2JavaModel;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.base.IChange;
import org.eclipse.jdt.internal.corext.refactoring.base.Refactoring;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.corext.refactoring.changes.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChange;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.textmanipulation.MultiTextEdit;
import org.eclipse.jdt.internal.corext.textmanipulation.TextBuffer;
import org.eclipse.jdt.internal.corext.textmanipulation.TextEdit;

public class ConvertAnonymousToNestedRefactoring extends Refactoring {

    private final int fSelectionStart;
    private final int fSelectionLength;
    private final ICompilationUnit fCu;
    
    private int fVisibility; /*see Modifier*/
    private boolean fDeclareFinal;
    private String fClassName;
    
    private CompilationUnit fCompilationUnitNode;
    private AnonymousClassDeclaration fAnonymousInnerClassNode;
    private Set fClassNamesUsed;
	
	public ConvertAnonymousToNestedRefactoring(ICompilationUnit cu, int selectionStart, int selectionLength){
		Assert.isTrue(selectionStart >= 0);
		Assert.isTrue(selectionLength >= 0);
		Assert.isTrue(cu.exists());
		fSelectionStart= selectionStart;
		fSelectionLength= selectionLength;
		fCu= cu;
	}
	
	public int[] getAvailableVisibilities(){
    	return new int[]{Modifier.PUBLIC, Modifier.PROTECTED, Modifier.NONE, Modifier.PRIVATE};
    }
    
    public int getVisibility() {
        return fVisibility;
    }
    
    public void setVisibility(int visibility) {
    	Assert.isTrue(	visibility == Modifier.PRIVATE ||
    					visibility == Modifier.NONE ||
    					visibility == Modifier.PROTECTED ||
    					visibility == Modifier.PUBLIC);
        fVisibility= visibility;
    }
    
    public void setClassName(String className){
    	Assert.isNotNull(className);
    	fClassName= className;
    }
    
    public boolean canEnableSettingFinal(){
    	return true;
    }

    public boolean getDeclareFinal(){
    	return fDeclareFinal;
    }

    public void setDeclareFinal(boolean declareFinal){
    	fDeclareFinal= declareFinal;
    }
    
    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.IRefactoring#getName()
     */
    public String getName() {
        return "Convert Anonymous to Inner";
    }
    
    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.Refactoring#checkActivation(org.eclipse.core.runtime.IProgressMonitor)
     */
    public RefactoringStatus checkActivation(IProgressMonitor pm) throws JavaModelException {
    	try{
    		RefactoringStatus result= Checks.validateModifiesFiles(ResourceUtil.getFiles(new ICompilationUnit[]{fCu}));
			if (result.hasFatalError())
				return result;

    		initAST();
    		
    		if (fAnonymousInnerClassNode == null)
    			return RefactoringStatus.createFatalErrorStatus("Place the caret inside an anonymous inner class");
    		
    		initializeDefaults();
	        return new RefactoringStatus();
    	} catch (CoreException e) {
    		throw new JavaModelException(e);
        } finally{
    		pm.done();
    	}
    }

    private void initializeDefaults() {
    	fVisibility= Modifier.PRIVATE;
    	fClassName= "";
    	fDeclareFinal= true;
    }
    
	private void initAST(){
		fCompilationUnitNode= AST.parseCompilationUnit(fCu, true);
		fAnonymousInnerClassNode= getAnonymousInnerClass(NodeFinder.perform(fCompilationUnitNode, fSelectionStart, fSelectionLength));
		if (fAnonymousInnerClassNode != null){
			TypeDeclaration[] nestedtypes= getTypeDeclaration().getTypes();
			fClassNamesUsed= new HashSet(nestedtypes.length);
			for (int i= 0; i < nestedtypes.length; i++) {
                fClassNamesUsed.add(nestedtypes[i].getName().getIdentifier());
            }
		}
	}
	
    private static AnonymousClassDeclaration getAnonymousInnerClass(ASTNode node) {
    	if (node == null)
    		return null;
    	if (node instanceof AnonymousClassDeclaration)
    		return (AnonymousClassDeclaration)node;
    	if (node instanceof ClassInstanceCreation){
    		AnonymousClassDeclaration anon= (AnonymousClassDeclaration)((ClassInstanceCreation)node).getAnonymousClassDeclaration();
    		if (anon != null)
    			return anon;
    	}	
    	if (node.getParent() instanceof ClassInstanceCreation){
    		AnonymousClassDeclaration anon= (AnonymousClassDeclaration)((ClassInstanceCreation)node.getParent()).getAnonymousClassDeclaration();
    		if (anon != null)
    			return anon;
    	}	
		return (AnonymousClassDeclaration)ASTNodes.getParent(node, AnonymousClassDeclaration.class);
    }
    
	public RefactoringStatus validateInput(){
		RefactoringStatus result= Checks.checkTypeName(fClassName);
		if (result.hasFatalError())
			return result;
			
		if (fClassNamesUsed.contains(fClassName))
			return RefactoringStatus.createFatalErrorStatus("Nested type with that name already exists");
		
		if (fClassName.equals(getSuperConstructorBinding().getDeclaringClass().getName()))	
			return RefactoringStatus.createFatalErrorStatus("Choose another name");
		
		if (classNameHidesEnclosingType())
			return RefactoringStatus.createFatalErrorStatus("Class name hides an enclosing type name");
		return result;	
	}
	
    private boolean classNameHidesEnclosingType() {
    	ITypeBinding type= getTypeDeclaration().resolveBinding();
    	while(type != null){
	    	if (fClassName.equals(type.getName()))
    			return true;
	    	type= type.getDeclaringClass();
    	}
        return false;
    }

    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.Refactoring#checkInput(org.eclipse.core.runtime.IProgressMonitor)
     */
    public RefactoringStatus checkInput(IProgressMonitor pm) throws JavaModelException {
    	try{
	        return validateInput();
    	} finally{
    		pm.done();
    	}
    }

    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.IRefactoring#createChange(org.eclipse.core.runtime.IProgressMonitor)
     */
    public IChange createChange(IProgressMonitor pm) throws JavaModelException {
    	pm.beginTask("", 1);
    	try{
    		ASTRewrite rewrite= new ASTRewrite(fCompilationUnitNode);
    		addNestedClass(rewrite);
    		modifyConstructorCall(rewrite);
            return createChange(rewrite);
    	} catch (CoreException e) {
    		throw new JavaModelException(e);
        } finally{
    		pm.done();
    	}
    }

    private IChange createChange(ASTRewrite rewrite) throws CoreException{
        TextChange change= new CompilationUnitChange("", fCu);
        TextBuffer textBuffer= TextBuffer.create(fCu.getBuffer().getContents());
        TextEdit resultingEdits= new MultiTextEdit();
        rewrite.rewriteNode(textBuffer, resultingEdits, null);
        change.addTextEdit("Convert anonymous inner class to a nested class", resultingEdits);
        rewrite.removeModifications();
        return change;
    }
    
    private void modifyConstructorCall(ASTRewrite rewrite) {
    	rewrite.markAsReplaced(getClassInstanceCreation(), createNewClassInstanceCreation(rewrite));
    }
    
    private ASTNode createNewClassInstanceCreation(ASTRewrite rewrite) {
    	ClassInstanceCreation newClassCreation= getAST().newClassInstanceCreation();
    	newClassCreation.setAnonymousClassDeclaration(null);
		newClassCreation.setName(getAST().newSimpleName(fClassName));
		copyArguments(rewrite, newClassCreation);
        addArgumentsForLocalsUsedInInnerClass(rewrite, newClassCreation);
        return newClassCreation;
    }

    private void addArgumentsForLocalsUsedInInnerClass(ASTRewrite rewrite, ClassInstanceCreation newClassCreation) {
        IVariableBinding[] usedLocals= getUsedLocalVariables();
        for (int i= 0; i < usedLocals.length; i++) {
            IVariableBinding local= usedLocals[i];
            Expression expression= getAST().newSimpleName(local.getName());
            rewrite.markAsInserted(expression);
            newClassCreation.arguments().add(expression);
        }
    }
    
    private void copyArguments(ASTRewrite rewrite, ClassInstanceCreation newClassCreation) {
    	for (Iterator iter= getClassInstanceCreation().arguments().iterator(); iter.hasNext();) {
            Expression arg= (Expression) iter.next();
            Expression copy= (Expression)rewrite.createCopy(arg);
            rewrite.markAsInserted(copy);
            newClassCreation.arguments().add(copy);
        }
    }
    
    private void addNestedClass(ASTRewrite rewrite) throws JavaModelException {
    	TypeDeclaration type= getTypeDeclaration();
    	List bodyDeclarations= type.bodyDeclarations();
    	int index= findIndexOfFistNestedClass(bodyDeclarations);
    	if (index == -1)
    		index= 0;
    	TypeDeclaration newNestedClass= createNewNestedClass(rewrite);
    	rewrite.markAsInserted(newNestedClass);
    	bodyDeclarations.add(index, newNestedClass);
    }
    
    private static int findIndexOfFistNestedClass(List bodyDeclarations) {
    	for (int i= 0, n= bodyDeclarations.size(); i < n; i++) {
    		BodyDeclaration each= (BodyDeclaration)bodyDeclarations.get(i);
    		if (isNestedType(each))
    			return i;
        }
        return -1;
    }
    
    private static boolean isNestedType(BodyDeclaration each) {
    	if (! (each instanceof TypeDeclaration))
	        return false;
	    return (each.getParent() instanceof TypeDeclaration);
    }

    private TypeDeclaration createNewNestedClass(ASTRewrite rewrite) throws JavaModelException {
    	TypeDeclaration newType= getAST().newTypeDeclaration();
    	newType.setInterface(false);
    	newType.setJavadoc(null);
    	newType.setModifiers(createModifiersForNestedClass());
    	newType.setName(getAST().newSimpleName(fClassName));
    	setSuperType(newType);
    	removeInitializationFromDeclaredFields(rewrite, newType);
        copyBodyDeclarationsToNestedClass(rewrite, newType);
        createFieldsForAccessedLocals(rewrite, newType);
	    createNewConstructorIfNeeded(rewrite, newType);
        return newType;
    }
    
    private void removeInitializationFromDeclaredFields(ASTRewrite rewrite, TypeDeclaration newType) {
		 for (Iterator iter= getFieldsToInitializeInConstructor().iterator(); iter.hasNext();) {
            VariableDeclarationFragment fragment= (VariableDeclarationFragment) iter.next();
            Assert.isNotNull(fragment.getInitializer());
            rewrite.markAsRemoved(fragment.getInitializer());
        }
    }
    
    private void createFieldsForAccessedLocals(ASTRewrite rewrite, TypeDeclaration newType) {
        IVariableBinding[] usedLocals= getUsedLocalVariables();
		for (int i= 0; i < usedLocals.length; i++) {
            IVariableBinding local= usedLocals[i];
            VariableDeclarationFragment fragment= getAST().newVariableDeclarationFragment();
            fragment.setExtraDimensions(0);
            fragment.setInitializer(null);
            fragment.setName(getAST().newSimpleName(local.getName()));
            FieldDeclaration field= getAST().newFieldDeclaration(fragment);
            field.setType(Bindings.createType(local.getType(), getAST(), false));
            field.setModifiers(Modifier.PRIVATE | Modifier.FINAL);
            newType.bodyDeclarations().add(findIndexOfLastField(newType.bodyDeclarations()) + 1, field);
            rewrite.markAsInserted(field);
        }    
    }
    
    private IVariableBinding[] getUsedLocalVariables(){
    	final Set result= new HashSet(0);
    	fAnonymousInnerClassNode.accept(createTempUsageFinder(result));
    	return (IVariableBinding[]) result.toArray(new IVariableBinding[result.size()]);
    }

    private ASTVisitor createTempUsageFinder(final Set result) {
        return new ASTVisitor(){
        	public boolean visit(SimpleName node) {
        		IBinding binding= node.resolveBinding();
        		if (ConvertAnonymousToNestedRefactoring.this.isBindingToTemp(binding))
        			result.add(binding);	
        		return true;
            }
        };
    }
    
    private boolean isBindingToTemp(IBinding binding){
		if (!(binding instanceof IVariableBinding))
			return false;
		if (! Modifier.isFinal(binding.getModifiers()))
			return false;
		ASTNode declaringNode= fCompilationUnitNode.findDeclaringNode(binding);
		if (declaringNode == null)
			return false;
		if (ASTNodes.isParent(declaringNode, fAnonymousInnerClassNode))
			return false;
		return true;	
    }
    
    private void createNewConstructorIfNeeded(ASTRewrite rewrite, TypeDeclaration newType) throws JavaModelException {
	    IVariableBinding[] usedLocals= getUsedLocalVariables();
    
    	if (getClassInstanceCreation().arguments().isEmpty() && usedLocals.length == 0)
    		return;
    	
    	MethodDeclaration newConstructor= getAST().newMethodDeclaration();
    	newConstructor.setConstructor(true);
    	newConstructor.setExtraDimensions(0);
    	newConstructor.setJavadoc(null);
    	newConstructor.setModifiers(fVisibility);
    	newConstructor.setName(getAST().newSimpleName(fClassName));
    	addParametersToNewConstructor(newConstructor, rewrite);
	    int paramCount= newConstructor.parameters().size();

        addParametersForLocalsUsedInInnerClass(rewrite, usedLocals, newConstructor);

        Block constructorBody= getAST().newBlock();
        SuperConstructorInvocation superConstructorInvocation= getAST().newSuperConstructorInvocation();
        for (int i= 0; i < paramCount; i++) {
            SingleVariableDeclaration param= (SingleVariableDeclaration) newConstructor.parameters().get(i);
            superConstructorInvocation.arguments().add(getAST().newSimpleName(param.getName().getIdentifier()));
        }
        constructorBody.statements().add(superConstructorInvocation);
        
        for (int i= 0; i < usedLocals.length; i++) {
            IVariableBinding local= usedLocals[i];
            String assignmentCode= ToolFactory.createCodeFormatter().format("this." + local.getName() + "=" + local.getName(), 0, null, getLineSeparator());
            Expression assignmentExpression= (Expression)rewrite.createPlaceholder(assignmentCode, ASTRewrite.EXPRESSION);
            ExpressionStatement assignmentStatement= getAST().newExpressionStatement(assignmentExpression);
	        constructorBody.statements().add(assignmentStatement);
        }
    	
        addFieldInitialization(rewrite, constructorBody);
        
    	newConstructor.setBody(constructorBody);
    	
		addExceptionsToNewConstructor(newConstructor, rewrite);
    	rewrite.markAsInserted(newConstructor);
    	int index= 1 + usedLocals.length + findIndexOfLastField(fAnonymousInnerClassNode.bodyDeclarations());
        newType.bodyDeclarations().add(index, newConstructor);
    }

    private void addFieldInitialization(ASTRewrite rewrite, Block constructorBody) {        
        for (Iterator iter= getFieldsToInitializeInConstructor().iterator(); iter.hasNext();) {
            VariableDeclarationFragment fragment= (VariableDeclarationFragment) iter.next();
            Assignment assignmentExpression= getAST().newAssignment();
            assignmentExpression.setOperator(Assignment.Operator.ASSIGN);
            assignmentExpression.setLeftHandSide(getAST().newSimpleName(fragment.getName().getIdentifier()));
            Expression rhs= (Expression)rewrite.createCopy(fragment.getInitializer());
            assignmentExpression.setRightHandSide(rhs);
            ExpressionStatement assignmentStatement= getAST().newExpressionStatement(assignmentExpression);
            constructorBody.statements().add(assignmentStatement);
        }
    }
    
    //live List of VariableDeclarationFragments
    private List getFieldsToInitializeInConstructor(){
    	List result= new ArrayList(0);
        for (Iterator iter= fAnonymousInnerClassNode.bodyDeclarations().iterator(); iter.hasNext();) {
            BodyDeclaration element= (BodyDeclaration) iter.next();
            if (!(element instanceof FieldDeclaration))
            	continue;
            FieldDeclaration field= (FieldDeclaration)element;
        	for (Iterator fragmentIter= field.fragments().iterator(); fragmentIter.hasNext();) {
                VariableDeclarationFragment fragment= (VariableDeclarationFragment) fragmentIter.next();
                if (isToBeInitializerInConstructor(fragment))
                	result.add(fragment);
            }
        }
        return result;
    }

    private boolean isToBeInitializerInConstructor(VariableDeclarationFragment fragment) {
    	if (fragment.getInitializer() == null)
    		return false;
    	return areLocalsUsedIn(fragment.getInitializer());	
    }
    
    private boolean areLocalsUsedIn(Expression fieldInitializer) {
    	Set localsUsed= new HashSet(0);
		fieldInitializer.accept(createTempUsageFinder(localsUsed));
        return ! localsUsed.isEmpty();
    }

    private void addParametersForLocalsUsedInInnerClass(ASTRewrite rewrite, IVariableBinding[] usedLocals, MethodDeclaration newConstructor) {
        for (int i= 0; i < usedLocals.length; i++) {
            IVariableBinding local= usedLocals[i];
            SingleVariableDeclaration param= createNewParamDeclarationNode(local.getName(), local.getType());
            rewrite.markAsInserted(param);
            newConstructor.parameters().add(param);
        }
    }
    
    private IMethodBinding getSuperConstructorBinding(){
    	//workaround for missing jcore functionality - finding a superconstructor for an anonymous class creation
    	IMethodBinding anonConstr= getClassInstanceCreation().resolveConstructorBinding();
    	ITypeBinding superClass= anonConstr.getDeclaringClass().getSuperclass();
    	IMethodBinding[] superMethods= superClass.getDeclaredMethods();
    	for (int i= 0; i < superMethods.length; i++) {
            IMethodBinding superMethod= superMethods[i];
            if (superMethod.isConstructor() && parameterTypesMatch(superMethod, anonConstr))	
            	return superMethod;
        }
        Assert.isTrue(false);//there's no way - it must be there
        return null;
    }
    
    private static boolean parameterTypesMatch(IMethodBinding m1, IMethodBinding m2) {
    	ITypeBinding[] m1Params= m1.getParameterTypes();
    	ITypeBinding[] m2Params= m2.getParameterTypes();
    	if (m1Params.length != m2Params.length)
	        return false;
	    for (int i= 0; i < m2Params.length; i++) {
	    	if (! m1Params[i].equals(m2Params[i]))
	    		return false;
        }    
        return true;
    }
    
    private void addExceptionsToNewConstructor(MethodDeclaration newConstructor, ASTRewrite rewrite) {
    	IMethodBinding constructorBinding= getSuperConstructorBinding();
    	if (constructorBinding == null)
    		return;
    	ITypeBinding[] exceptions= constructorBinding.getExceptionTypes();
    	for (int i= 0; i < exceptions.length; i++) {
            Name exceptionTypeName= getAST().newName(Bindings.getNameComponents(exceptions[i]));
            newConstructor.thrownExceptions().add(exceptionTypeName);
        }	
    }
    
    private void addParametersToNewConstructor(MethodDeclaration newConstructor, ASTRewrite rewrite) throws JavaModelException {
    	IMethodBinding constructorBinding= getSuperConstructorBinding();
    	if (constructorBinding == null)
    		return;
    	ITypeBinding[] paramTypes= constructorBinding.getParameterTypes();
    	IMethod method= Binding2JavaModel.find(constructorBinding, fCu.getJavaProject());
    	if (method == null)
    		return;
    	String[] parameterNames= method.getParameterNames();
    	for (int i= 0; i < parameterNames.length; i++) {
            SingleVariableDeclaration param= createNewParamDeclarationNode(parameterNames[i], paramTypes[i]);
            rewrite.markAsInserted(param);
            newConstructor.parameters().add(param);
        }
    }

    private SingleVariableDeclaration createNewParamDeclarationNode(String paramName, ITypeBinding paramType) {
        SingleVariableDeclaration param= getAST().newSingleVariableDeclaration();
        param.setExtraDimensions(0);
        param.setInitializer(null);
        param.setModifiers(Modifier.NONE);
        param.setName(getAST().newSimpleName(paramName));
        param.setType(Bindings.createType(paramType, getAST(), false));
        return param;
    }

    private void copyBodyDeclarationsToNestedClass(ASTRewrite rewrite, TypeDeclaration newType) {
        for (Iterator iter= fAnonymousInnerClassNode.bodyDeclarations().iterator(); iter.hasNext();) {
            BodyDeclaration element= (BodyDeclaration ) iter.next();
            BodyDeclaration copy= (BodyDeclaration)rewrite.createCopy(element);
            rewrite.markAsInserted(copy);
            newType.bodyDeclarations().add(copy);
        }
    }
    
    private void setSuperType(TypeDeclaration newType) throws JavaModelException {
    	ITypeBinding binding= getClassInstanceCreation().resolveTypeBinding();
    	if (binding == null)
    		return;
    	if (binding.getSuperclass().getQualifiedName().equals("java.lang.Object")){	
	    	Assert.isTrue(binding.getInterfaces().length <= 1);
    		if (binding.getInterfaces().length == 0)
	    		return;
	    	newType.superInterfaces().add(0, getSuperTypeName());
    	} else {	
	    	newType.setSuperclass(getSuperTypeName());
    	}	
    }

    private Name getSuperTypeName() throws JavaModelException {
        return getAST().newName(getIdentiiers(getNodeSourceCode(getClassInstanceCreation().getName())));
    }
    
    private static String[] getIdentiiers(String nameCode) {
    	StringTokenizer tokenizer= new StringTokenizer(nameCode, ".");
    	String[] tokens= new String[tokenizer.countTokens()];
    	for (int i= 0; tokenizer.hasMoreTokens(); i++) {
            tokens[i]= tokenizer.nextToken();
        }
        return tokens;
    }
    
    private String getNodeSourceCode(ASTNode node) throws JavaModelException{
        return fCu.getBuffer().getText(node.getStartPosition(), node.getLength());
    }
    
    private int createModifiersForNestedClass() {
        int flags= fVisibility;
        if (fDeclareFinal)
        	flags |= Modifier.FINAL;
        return flags;	
    }

    private AST getAST() {
        return fAnonymousInnerClassNode.getAST();
    }
    
    private ClassInstanceCreation getClassInstanceCreation(){
    	return (ClassInstanceCreation)fAnonymousInnerClassNode.getParent();
    }
    
	private TypeDeclaration getTypeDeclaration(){
		return (TypeDeclaration)ASTNodes.getParent(fAnonymousInnerClassNode, TypeDeclaration.class);
	}

    private String getLineSeparator() {
		try {
			return StubUtility.getLineDelimiterUsed(fCu);
		} catch (JavaModelException e) {
			return System.getProperty("line.separator", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
		}
    }    

    private static int findIndexOfLastField(List bodyDeclarations) {
    	for (int i= bodyDeclarations.size() - 1; i >= 0; i--) {
    		BodyDeclaration each= (BodyDeclaration)bodyDeclarations.get(i);
    		if (each instanceof FieldDeclaration)
    			return i;
        }
        return -1;
    }
}
