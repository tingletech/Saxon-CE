package client.net.sf.saxon.ce.expr;

import client.net.sf.saxon.ce.Configuration;
import client.net.sf.saxon.ce.Controller;
import client.net.sf.saxon.ce.event.PipelineConfiguration;
import client.net.sf.saxon.ce.event.SequenceOutputter;
import client.net.sf.saxon.ce.expr.instruct.SlotManager;
import client.net.sf.saxon.ce.functions.Current;
import client.net.sf.saxon.ce.lib.NamespaceConstant;
import client.net.sf.saxon.ce.om.*;
import client.net.sf.saxon.ce.trans.XPathException;
import client.net.sf.saxon.ce.tree.util.SourceLocator;
import client.net.sf.saxon.ce.value.*;
import client.net.sf.saxon.ce.value.StringValue;

import java.util.Iterator;
import java.util.List;

/**
 * This class, ExpressionTool, contains a number of useful static methods
 * for manipulating expressions. Most importantly, it provides the factory
 * method make() for constructing a new expression
 */

public class ExpressionTool {

    public static final int UNDECIDED = -1;
    public static final int NO_EVALUATION_NEEDED = 0;
    public static final int EVALUATE_VARIABLE = 1;
    public static final int MAKE_CLOSURE = 3;
    public static final int MAKE_MEMO_CLOSURE = 4;
    public static final int RETURN_EMPTY_SEQUENCE = 5;
    public static final int EVALUATE_AND_MATERIALIZE_VARIABLE = 6;
    public static final int CALL_EVALUATE_ITEM = 7;
    public static final int ITERATE_AND_MATERIALIZE = 8;
    public static final int PROCESS = 9;
    public static final int MAKE_INDEXED_VARIABLE = 12;
    public static final int MAKE_SINGLETON_CLOSURE = 13;
    public static final int EVALUATE_SUPPLIED_PARAMETER = 14;

    private ExpressionTool() {}

    /**
     * Parse an XPath expression. This performs the basic analysis of the expression against the
     * grammar, it binds variable references and function calls to variable definitions and
     * function definitions, and it performs context-independent expression rewriting for
     * optimization purposes.
     *
     * @param expression The expression (as a character string)
     * @param env An object giving information about the compile-time
 *     context of the expression
     * @param container
     * @param start position of the first significant character in the expression
     * @param terminator The token that marks the end of this expression; typically
* Token.EOF, but may for example be a right curly brace
     * @param locator the source location of the expression for use in diagnostics
     */

    public static Expression make(String expression, StaticContext env,
                                  Container container, int start, int terminator, SourceLocator locator
    ) throws XPathException {
        ExpressionParser parser = new ExpressionParser();
        parser.setDefaultContainer(container);
        if (terminator == -1) {
            terminator = Token.EOF;
        }
        Expression exp = parser.parse(expression, start, terminator, env);
        exp = ExpressionVisitor.make(env, exp.getExecutable()).simplify(exp);
        exp.setSourceLocator(locator);
        return exp;
    }

    /**
     * Copy location information (the line number and reference to the container) from one expression
     * to another
     * @param from the expression containing the location information
     * @param to the expression to which the information is to be copied
     */

    public static void copyLocationInfo(Expression from, Expression to) {
        if (from != null && to != null) {
        	if(from.sourceLocator != null){
	            to.setSourceLocator(from.getSourceLocator());
	            to.setContainer(from.getContainer());
        	}
        }
    }

    /**
     * Remove unwanted sorting from an expression, at compile time
     * @param opt the expression optimizer
     * @param exp the expression to be optimized
     * @param retainAllNodes true if there is a need to retain exactly those nodes returned by exp
     * even if there are duplicates; false if the caller doesn't mind whether duplicate nodes
     * are retained or eliminated
     * @return the expression after rewriting
     */

    public static Expression unsorted(Optimizer opt, Expression exp, boolean retainAllNodes)
    throws XPathException {
        if (exp instanceof Literal) {
            return exp;   // fast exit
        }
        PromotionOffer offer = new PromotionOffer(opt);
        offer.action = PromotionOffer.UNORDERED;
        offer.retainAllNodes = retainAllNodes;
        return exp.promote(offer, null);
    }

    /**
     * Determine the method of evaluation to be used when lazy evaluation of an expression is
     * preferred. This method is called at compile time, after all optimizations have been done,
     * to determine the preferred strategy for lazy evaluation, depending on the type of expression.
     *
     * @param exp the expression to be evaluated
     * @return an integer constant identifying the evaluation mode
     */

    public static int lazyEvaluationMode(Expression exp) {
        if (exp instanceof Literal) {
            return NO_EVALUATION_NEEDED;

        } else if (exp instanceof VariableReference) {
            return EVALUATE_VARIABLE;

        } else if (exp instanceof SuppliedParameterReference) {
            return EVALUATE_SUPPLIED_PARAMETER;

        } else if ((exp.getDependencies() &
                (   StaticProperty.DEPENDS_ON_POSITION |
                    StaticProperty.DEPENDS_ON_LAST |
                    StaticProperty.DEPENDS_ON_CURRENT_ITEM |
                    StaticProperty.DEPENDS_ON_CURRENT_GROUP |
                    StaticProperty.DEPENDS_ON_REGEX_GROUP )) != 0) {
            // we can't save these values in the closure, so we evaluate
            // the expression now if they are needed
            return eagerEvaluationMode(exp);

        } else if (exp instanceof ErrorExpression) {
            return CALL_EVALUATE_ITEM;
                // evaluateItem() on an error expression throws the latent exception

        } else if (!Cardinality.allowsMany(exp.getCardinality())) {
            // singleton expressions are always evaluated eagerly
            return eagerEvaluationMode(exp);

        } else {
            // create a Closure, a wrapper for the expression and its context
            return MAKE_CLOSURE;
        }
    }

    /**
     * Determine the method of evaluation to be used when lazy evaluation of an expression is
     * preferred. This method is called at compile time, after all optimizations have been done,
     * to determine the preferred strategy for lazy evaluation, depending on the type of expression.
     *
     * @param exp the expression to be evaluated
     * @return an integer constant identifying the evaluation mode
     */

    public static int eagerEvaluationMode(Expression exp) {
        if (exp instanceof Literal && !(((Literal)exp).getValue() instanceof Closure)) {
            return NO_EVALUATION_NEEDED;
        }
        if (exp instanceof VariableReference) {
            return EVALUATE_AND_MATERIALIZE_VARIABLE;
        }
        int m = exp.getImplementationMethod();
        if ((m & Expression.EVALUATE_METHOD) != 0) {
            return CALL_EVALUATE_ITEM;
        } else if ((m & Expression.ITERATE_METHOD) != 0) {
            return ITERATE_AND_MATERIALIZE;
        } else {
            return PROCESS;
        }
    }


    /**
     * Do lazy evaluation of an expression. This will return a value, which may optionally
     * be a SequenceIntent, which is a wrapper around an iterator over the value of the expression.
     * @param exp the expression to be evaluated
     * @param evaluationMode the evaluation mode for this expression
     * @param context the run-time evaluation context for the expression. If
     *     the expression is not evaluated immediately, then parts of the
     *     context on which the expression depends need to be saved as part of
     *      the Closure
     * @param ref an indication of how the value will be used. The value 1 indicates that the value
     *     is only expected to be used once, so that there is no need to keep it in memory. A small value >1
     *     indicates multiple references, so the value will be saved when first evaluated. The special value
     *     FILTERED indicates a reference within a loop of the form $x[predicate], indicating that the value
     *     should be saved in a way that permits indexing.
     * @exception XPathException if any error occurs in evaluating the
     *     expression
     * @return a value: either the actual value obtained by evaluating the
     *     expression, or a Closure containing all the information needed to
     *     evaluate it later
     */

    public static ValueRepresentation evaluate(Expression exp, int evaluationMode, XPathContext context, int ref)
    throws XPathException {
        switch (evaluationMode) {

            case NO_EVALUATION_NEEDED:
                return ((Literal)exp).getValue();

            case EVALUATE_VARIABLE:
                return ((VariableReference)exp).evaluateVariable(context);

            case EVALUATE_SUPPLIED_PARAMETER:
                return ((SuppliedParameterReference)exp).evaluateVariable(context);

            case MAKE_CLOSURE:
                return Closure.make(exp, context, ref);
                //return new SequenceExtent(exp.iterate(context));

            case MAKE_MEMO_CLOSURE:
                return Closure.make(exp, context, (ref==1 ? 10 : ref));

            case MAKE_SINGLETON_CLOSURE:
                return new SingletonClosure(exp, context);

            case RETURN_EMPTY_SEQUENCE:
                return EmptySequence.getInstance();

            case EVALUATE_AND_MATERIALIZE_VARIABLE:
                ValueRepresentation v = ((VariableReference)exp).evaluateVariable(context);
                if (v instanceof Closure) {
                    return SequenceExtent.makeSequenceExtent(((Closure)v).iterate());
                } else {
                    return v;
                }

            case CALL_EVALUATE_ITEM:
                Item item = exp.evaluateItem(context);
                if (item == null) {
                    return EmptySequence.getInstance();
                } else {
                    return item;
                }

            case UNDECIDED:
            case ITERATE_AND_MATERIALIZE:
                return SequenceExtent.makeSequenceExtent(exp.iterate(context));

            case PROCESS:
                Controller controller = context.getController();
                XPathContext c2 = context.newMinorContext();
                SequenceOutputter seq = controller.allocateSequenceOutputter(20);
                PipelineConfiguration pipe = controller.makePipelineConfiguration();
                seq.setPipelineConfiguration(pipe);
                c2.setTemporaryReceiver(seq);
                seq.open();
                exp.process(c2);
                seq.close();
                ValueRepresentation val = seq.getSequence();
                seq.reset();
                return val;

            default:
                throw new IllegalArgumentException("Unknown evaluation mode " + evaluationMode);

        }
    }

    /**
     * Do lazy evaluation of an expression. This will return a value, which may optionally
     * be a SequenceIntent, which is a wrapper around an iterator over the value of the expression.
     * @param exp the expression to be evaluated
     * @param context the run-time evaluation context for the expression. If
     *     the expression is not evaluated immediately, then parts of the
     *     context on which the expression depends need to be saved as part of
     *      the Closure
     * @param ref an indication of how the value will be used. The value 1 indicates that the value
     *     is only expected to be used once, so that there is no need to keep it in memory. A small value >1
     *     indicates multiple references, so the value will be saved when first evaluated. The special value
     *     FILTERED indicates a reference within a loop of the form $x[predicate], indicating that the value
     *     should be saved in a way that permits indexing.
     * @return a value: either the actual value obtained by evaluating the
     *     expression, or a Closure containing all the information needed to
     *     evaluate it later
     * @throws XPathException if any error occurs in evaluating the
     *     expression
     */

    public static ValueRepresentation lazyEvaluate(Expression exp, XPathContext context, int ref) throws XPathException {
        final int evaluationMode = lazyEvaluationMode(exp);
        return evaluate(exp, evaluationMode, context, ref);
    }

    /**
     * Scan an expression to find and mark any recursive tail function calls
     * @param exp the expression to be analyzed
     * @param qName the name of the containing function
     * @param arity the arity of the containing function
     * @return 0 if no tail call was found; 1 if a tail call to a different function was found;
     * 2 if a tail call to the specified function was found. In this case the
     * UserFunctionCall object representing the tail function call will also have been marked as
     * a tail call.
     */

    public static int markTailFunctionCalls(Expression exp, StructuredQName qName, int arity) {
        return exp.markTailFunctionCalls(qName, arity);
    }

    /**
     * Allocate slot numbers to range variables
     * @param exp the expression whose range variables need to have slot numbers assigned
     * @param nextFree the next slot number that is available for allocation
     * @param frame a SlotManager object that is used to track the mapping of slot numbers
     * to variable names for debugging purposes. May be null.
     * @return the next unallocated slot number.
    */

    public static int allocateSlots(Expression exp, int nextFree, SlotManager frame) {
        if (exp instanceof Assignation) {
            ((Assignation)exp).setSlotNumber(nextFree);
            int count = ((Assignation)exp).getRequiredSlots();
            nextFree += count;
            if (frame != null) {
                frame.allocateSlotNumber(((Assignation)exp).getVariableQName());
            }
        }
        if (exp instanceof VariableReference) {
            VariableReference var = (VariableReference)exp;
            Binding binding = var.getBinding();
            if (exp instanceof LocalVariableReference) {
                ((LocalVariableReference)var).setSlotNumber(binding.getLocalSlotNumber());
            }
            if (binding instanceof Assignation && binding.getLocalSlotNumber() < 0) {
                // This indicates something badly wrong: we've found a variable reference on the tree, that's
                // bound to a variable declaration that is no longer on the tree. All we can do is print diagnostics.
                // The most common reason for this failure is that the declaration of the variable was removed
                // from the tree in the mistaken belief that there were no references to the variable. Variable
                // references are counted during the typeCheck phase, so this can happen if typeCheck() fails to
                // visit some branch of the expression tree.
                Assignation decl = (Assignation)binding;
                String msg = "*** Internal Saxon error: local variable encountered whose binding has been deleted";
                System.err.println(msg);
                System.err.println("Variable name: " + decl.getVariableName());
                System.err.println(decl.toString());
                throw new IllegalStateException(msg);
            }

        }
        for (Iterator children = exp.iterateSubExpressions(); children.hasNext();) {
            Expression child = (Expression)children.next();
            nextFree = allocateSlots(child, nextFree, frame);
        }

        return nextFree;

        // Note, we allocate a distinct slot to each range variable, even if the
        // scopes don't overlap. This isn't strictly necessary, but might help
        // debugging.
    }

    /**
     * Determine the effective boolean value of a sequence, given an iterator over the sequence
     * @param iterator An iterator over the sequence whose effective boolean value is required
     * @return the effective boolean value
     * @throws XPathException if a dynamic error occurs
     */
    public static boolean effectiveBooleanValue(SequenceIterator iterator) throws XPathException {
        Item first = iterator.next();
        if (first == null) {
            return false;
        }
        if (first instanceof NodeInfo) {
            return true;
        } else {
            //first = ((AtomicValue)first).getPrimitiveValue();
            if (first instanceof BooleanValue) {
                if (iterator.next() != null) {
                    ebvError("a sequence of two or more items starting with a boolean");
                }
                return ((BooleanValue)first).getBooleanValue();
            } else if (first instanceof StringValue) {   // includes anyURI value
                if (iterator.next() != null) {
                    ebvError("a sequence of two or more items starting with a string");
                }
                return (!((StringValue)first).isZeroLength());
            } else if (first instanceof NumericValue) {
                if (iterator.next() != null) {
                    ebvError("a sequence of two or more items starting with a numeric value");
                }
                final NumericValue n = (NumericValue)first;
                return (n.compareTo(0) != 0) && !n.isNaN();
             } else {
                ebvError("a sequence starting with an atomic value other than a boolean, number, string, or URI");
                return false;
            }
        }
    }

    /**
     * Determine the effective boolean value of a single item
     * @param item the item whose effective boolean value is required
     * @return the effective boolean value
     * @throws XPathException if a dynamic error occurs
     */
    public static boolean effectiveBooleanValue(Item item) throws XPathException {
        if (item == null) {
            return false;
        }
        if (item instanceof NodeInfo) {
            return true;
        } else {
            if (item instanceof BooleanValue) {
                return ((BooleanValue)item).getBooleanValue();
            } else if (item instanceof StringValue) {   // includes anyURI value
                return !((StringValue)item).isZeroLength();
            } else if (item instanceof NumericValue) {
                final NumericValue n = (NumericValue)item;
                return (n.compareTo(0) != 0) && !n.isNaN();
            } else {
                ebvError("an item other than a boolean, number, string, or URI");
                return false;
            }
        }
    }

    /**
     * Report an error in computing the effective boolean value of an expression
     * @param reason the nature of the error
     * @throws XPathException
     */

    public static void ebvError(String reason) throws XPathException {
        XPathException err = new XPathException("Effective boolean value is not defined for " + reason);
        err.setErrorCode("FORG0006");
        err.setIsTypeError(true);
        throw err;
    }

    /**
     * Ask whether an expression has a dependency on the focus
     * @param exp the expression
     * @return true if the value of the expression depends on the context item, position, or size
     */

    public static boolean dependsOnFocus(Expression exp) {
        return ((exp.getDependencies() & StaticProperty.DEPENDS_ON_FOCUS) != 0);
    }    

    /**
     * Determine whether an expression depends on any one of a set of variables
     * @param e the expression being tested
     * @param bindingList the set of variables being tested
     * @return true if the expression depends on one of the given variables
     */

    public static boolean dependsOnVariable(Expression e, Binding[] bindingList) {
        if (bindingList == null || bindingList.length == 0) {
            return false;
        }
        if (e instanceof VariableReference) {
            for (int i=0; i<bindingList.length; i++) {
                if (((VariableReference)e).getBinding() == bindingList[i]) {
                    return true;
                }
            }
            return false;
//        } else if ((e.getDependencies() & StaticProperty.DEPENDS_ON_LOCAL_VARIABLES) == 0) {
//            return false;
        } else {
            for (Iterator children = e.iterateSubExpressions(); children.hasNext();) {
                Expression child = (Expression)children.next();
                if (dependsOnVariable(child, bindingList)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Determine whether an expression contains a call on the function with a given fingerprint
     * @param exp The expression being tested
     * @param qName The name of the function
     * @return true if the expression contains a call on the function
     */

    public static boolean callsFunction(Expression exp, StructuredQName qName) {
        if (exp instanceof FunctionCall && (((FunctionCall)exp).getFunctionName().equals(qName))) {
            return true;
        }
        Iterator iter = exp.iterateSubExpressions();
        while (iter.hasNext()) {
            Expression e = (Expression)iter.next();
            if (callsFunction(e, qName)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Resolve calls to the XSLT current() function within an expression
     * @param exp the expression within which calls to current() should be resolved
     * @param config the Saxon configuration
     * @return the expression after resolving calls to current()
     */

    public static Expression resolveCallsToCurrentFunction(Expression exp, Configuration config)
            throws XPathException {
        if (callsFunction(exp, Current.FN_CURRENT)) {
            LetExpression let = new LetExpression();
            let.setVariableQName(
                    new StructuredQName("saxon", NamespaceConstant.SAXON, "current" + exp.hashCode()));
            let.setRequiredType(SequenceType.SINGLE_ITEM);
            let.setSequence(new CurrentItemExpression());
            PromotionOffer offer = new PromotionOffer(config.getOptimizer());
            offer.action = PromotionOffer.REPLACE_CURRENT;
            offer.containingExpression = let;
            exp = exp.promote(offer, null);
            let.setAction(exp);
            return let;
        } else {
            return exp;
        }
    }

    /**
     * Get a list of all references to a particular variable within a subtree
     * @param exp the expression at the root of the subtree
     * @param binding the variable binding whose references are sought
     * @param list a list to be populated with the references to this variable
     */

    public static void gatherVariableReferences(Expression exp, Binding binding, List list) {
        if (exp instanceof VariableReference &&
                ((VariableReference)exp).getBinding() == binding) {
            list.add(exp);
        } else {
            for (Iterator iter = exp.iterateSubExpressions(); iter.hasNext(); ) {
                gatherVariableReferences((Expression)iter.next(), binding, list);
            }
        }
    }

    /**
     * Determine how often a variable is referenced. This is the number of times
     * it is referenced at run-time: so a reference in a loop counts as "many". This code
     * currently handles local variables (Let expressions) and function parameters. It is
     * not currently used for XSLT template parameters. It's not the end of the world if
     * the answer is wrong (unless it's wrongly given as zero), but if wrongly returned as
     * 1 then the variable will be repeatedly evaluated.
     * @param exp the expression within which variable references are to be counted
     * @param binding identifies the variable of interest
     * @param inLoop true if the expression is within a loop, in which case a reference counts as many.
     * This should be set to false on the initial call, it may be set to true on an internal recursive
     * call 
     * @return the number of references. The interesting values are 0, 1,  "many" (represented
     * by any value >1), and the special value FILTERED, which indicates that there are
     * multiple references and one or more of them is of the form $x[....] indicating that an
     * index might be useful.
     */

    public static int getReferenceCount(Expression exp, Binding binding, boolean inLoop) {
        int rcount = 0;
        if (exp instanceof VariableReference && ((VariableReference)exp).getBinding() == binding) {
            rcount += (inLoop ? 10 : 1);
        } else if ((exp.getDependencies() & StaticProperty.DEPENDS_ON_LOCAL_VARIABLES) == 0) {
            return 0;
        } else {
            for (Iterator iter = exp.iterateSubExpressions(); iter.hasNext(); ) {
                Expression child = (Expression)iter.next();
                boolean childLoop = inLoop || (exp.hasLoopingSubexpression(child));
                rcount += getReferenceCount(child, binding, childLoop);
            }
        }
        return rcount;
    }


    /**
     * Rebind all variable references to a binding
     * @param exp the expression whose contained variable references are to be rebound
     * @param oldBinding the old binding for the variable references
     * @param newBinding the new binding to which the variables should be rebound
     */

    public static void rebindVariableReferences(
            Expression exp, Binding oldBinding, Binding newBinding) {
        if (exp instanceof VariableReference) {
            if (((VariableReference)exp).getBinding() == oldBinding) {
                ((VariableReference)exp).fixup(newBinding);
            }
        } else {
            Iterator iter = exp.iterateSubExpressions();
            while (iter.hasNext()) {
                Expression e = (Expression)iter.next();
                rebindVariableReferences(e, oldBinding, newBinding);
            }
        }
    }


}

// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. 
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is “Incompatible With Secondary Licenses”, as defined by the Mozilla Public License, v. 2.0.
