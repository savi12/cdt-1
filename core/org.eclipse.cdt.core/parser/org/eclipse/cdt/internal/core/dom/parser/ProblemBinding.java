/*******************************************************************************
 * Copyright (c) 2004, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Andrew Niefer (IBM Corporation) - initial API and implementation
 *     Markus Schorn (Wind River Systems)
 *     Bryan Wilkinson (QNX)
 *******************************************************************************/
package org.eclipse.cdt.internal.core.dom.parser;

import java.text.MessageFormat;

import org.eclipse.cdt.core.dom.ILinkage;
import org.eclipse.cdt.core.dom.IName;
import org.eclipse.cdt.core.dom.ast.DOMException;
import org.eclipse.cdt.core.dom.ast.EScopeKind;
import org.eclipse.cdt.core.dom.ast.IASTFileLocation;
import org.eclipse.cdt.core.dom.ast.IASTName;
import org.eclipse.cdt.core.dom.ast.IASTNode;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.IProblemBinding;
import org.eclipse.cdt.core.dom.ast.IScope;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.index.IIndexFileSet;
import org.eclipse.cdt.core.parser.util.CharArrayUtils;
import org.eclipse.cdt.internal.core.dom.Linkage;
import org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.CPPSemantics;
import org.eclipse.cdt.internal.core.parser.ParserMessages;
import org.eclipse.core.runtime.PlatformObject;

/**
 * Implementation of problem bindings
 */
public class ProblemBinding extends PlatformObject implements IProblemBinding, IType, IScope, IASTInternalScope {
    protected final int id;
    protected char[] arg;
    protected IASTNode node;
    private String message = null;
    
    public ProblemBinding(IASTNode node, int id, char[] arg) {
        this.id = id;
        this.arg = arg;
        this.node = node;
    }
    
	public EScopeKind getKind() {
		return EScopeKind.eLocal;
	}

    public IASTNode getASTNode() {
        return node;
    }
    
    protected static final String[] errorMessages;
    static {
        errorMessages = new String[IProblemBinding.SEMANTIC_INVALID_TEMPLATE_ARGUMENTS];
        errorMessages[SEMANTIC_NAME_NOT_FOUND - 1] 		 		= ParserMessages.getString("ASTProblemFactory.error.semantic.nameNotFound"); //$NON-NLS-1$
        errorMessages[SEMANTIC_AMBIGUOUS_LOOKUP - 1]			= ParserMessages.getString("ASTProblemFactory.error.semantic.pst.ambiguousLookup"); //$NON-NLS-1$ 
        errorMessages[SEMANTIC_INVALID_TYPE - 1]				= ParserMessages.getString("ASTProblemFactory.error.semantic.pst.invalidType"); //$NON-NLS-1$ 
        errorMessages[SEMANTIC_CIRCULAR_INHERITANCE - 1]		= ParserMessages.getString("ASTProblemFactory.error.semantic.pst.circularInheritance"); //$NON-NLS-1$
        errorMessages[SEMANTIC_INVALID_OVERLOAD - 1]			= ParserMessages.getString("ASTProblemFactory.error.semantic.pst.invalidOverload"); //$NON-NLS-1$
        errorMessages[SEMANTIC_INVALID_USING - 1]				= ParserMessages.getString("ASTProblemFactory.error.semantic.pst.invalidUsing"); //$NON-NLS-1$
        errorMessages[SEMANTIC_DEFINITION_NOT_FOUND - 1]		= ParserMessages.getString("ASTProblemFactory.error.semantic.dom.definitionNotFound"); //$NON-NLS-1$
        errorMessages[SEMANTIC_KNR_PARAMETER_DECLARATION_NOT_FOUND - 1] = ParserMessages.getString("ASTProblemFactory.error.semantic.dom.knrParameterDeclarationNotFound"); //$NON-NLS-1$
        errorMessages[SEMANTIC_LABEL_STATEMENT_NOT_FOUND - 1]	= ParserMessages.getString("ASTProblemFactory.error.semantic.dom.labelStatementNotFound"); //$NON-NLS-1$
        errorMessages[SEMANTIC_INVALID_REDEFINITION - 1]		= ParserMessages.getString("ASTProblemFactory.error.semantic.dom.invalidRedefinition"); //$NON-NLS-1$
        errorMessages[SEMANTIC_INVALID_REDECLARATION - 1]		= ParserMessages.getString("ASTProblemFactory.error.semantic.dom.invalidRedeclaration"); //$NON-NLS-1$
        errorMessages[SEMANTIC_BAD_SCOPE - 1]					= ParserMessages.getString("ASTProblemFactory.error.semantic.dom.badScope"); //$NON-NLS-1$
        errorMessages[SEMANTIC_RECURSION_IN_LOOKUP - 1]			= ParserMessages.getString("ASTProblemFactory.error.semantic.dom.recursionInResolution"); //$NON-NLS-1$
        errorMessages[SEMANTIC_MEMBER_DECLARATION_NOT_FOUND - 1]= ParserMessages.getString("ASTProblemFactory.error.semantic.dom.memberDeclNotFound"); //$NON-NLS-1$
        errorMessages[SEMANTIC_INVALID_TEMPLATE_ARGUMENTS - 1]=   ParserMessages.getString("ASTProblemFactory.error.semantic.dom.invalidTemplateArgs"); //$NON-NLS-1$
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IProblemBinding#getID()
     */
    public int getID() {
        return id;
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IProblemBinding#getMessage()
     */
    public String getMessage() {
        if (message != null)
            return message;

        String msg = (id > 0 && id <= errorMessages.length) ? errorMessages[id - 1] : ""; //$NON-NLS-1$

        if (arg != null) {
            msg = MessageFormat.format(msg, new Object[] { new String(arg) });
        }

		return msg;
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IBinding#getName()
     */
    public String getName() {
        return CPPSemantics.EMPTY_NAME;
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IBinding#getNameCharArray()
     */
    public char[] getNameCharArray() {
        return CharArrayUtils.EMPTY;
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IBinding#getScope()
     */
    public IScope getScope() throws DOMException {
        throw new DOMException(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IBinding#getPhysicalNode()
     */
    public IASTNode getPhysicalNode() {
        return getASTNode();
    }

    @Override
	public Object clone() {
    	// Don't clone problems
        return this;
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#getParent()
     */
    public IScope getParent() throws DOMException {
        throw new DOMException(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#find(java.lang.String)
     */
    public IBinding[] find(String name) throws DOMException {
        throw new DOMException(this);
    }

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.core.dom.ast.IScope#getScopeName()
	 */
	public IName getScopeName() {
		return null;
	}

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#addName(org.eclipse.cdt.core.dom.ast.IASTName)
     */
    public void addName(IASTName name) throws DOMException {
        throw new DOMException(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#removeBinding(org.eclipse.cdt.core.dom.ast.IBinding)
     */
    public void removeBinding(IBinding binding) throws DOMException {
        throw new DOMException(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#getBinding(org.eclipse.cdt.core.dom.ast.IASTName, boolean)
     */
    public IBinding getBinding(IASTName name, boolean resolve) throws DOMException {
        throw new DOMException(this);
    }

	public final IBinding[] getBindings(IASTName name, boolean resolve, boolean prefix)
			throws DOMException {
        throw new DOMException(this);
	}

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#getBinding(org.eclipse.cdt.core.dom.ast.IASTName, boolean)
     */
    public IBinding getBinding(IASTName name, boolean resolve, IIndexFileSet fileSet) throws DOMException {
        throw new DOMException(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#getBinding(org.eclipse.cdt.core.dom.ast.IASTName, boolean)
     */
    public IBinding[] getBindings(IASTName name, boolean resolve, boolean prefixLookup, IIndexFileSet fileSet) throws DOMException {
        throw new DOMException(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#setFullyCached(boolean)
     */
    public void setFullyCached(boolean b) {
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IScope#isFullyCached()
     */
    public boolean isFullyCached() throws DOMException {
        throw new DOMException(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.cdt.core.dom.ast.IType#isSameType(org.eclipse.cdt.core.dom.ast.IType)
     */
    public boolean isSameType(IType type) {
        return type == this;
    }

	public void flushCache() {
	}

	public String getFileName() {
		if (node != null)
			return node.getContainingFilename();

		return ""; //$NON-NLS-1$
	}

	public int getLineNumber() {
		if (node != null) {
			IASTFileLocation fileLoc = node.getFileLocation();
			if (fileLoc != null)
				return fileLoc.getStartingLineNumber();
		}
		return -1;
	}

	public void addBinding(IBinding binding) throws DOMException {
		throw new DOMException(this);
	}

	public ILinkage getLinkage() {
		return Linkage.NO_LINKAGE;
	}
	
	@Override
	public String toString() {
		return getMessage();
	}

	public IBinding getOwner() throws DOMException {
		return null;
	}

	public void setASTNode(IASTNode node, char[] arg) {
		if (node != null)
			this.node= node;
		if (arg != null)
			this.arg= arg;
	}
}
