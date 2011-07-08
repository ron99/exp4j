package de.congrace.exp4j;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This is Builder implementation for the exp4j API used to create a
 * Calculatable instance for the user
 * 
 * @author ruckus
 * 
 */
public class ExpressionBuilder {
	private final Map<String, Double> variables = new LinkedHashMap<String, Double>();
	private final Set<CustomFunction> customFunctions = new HashSet<CustomFunction>();

	private String expression;

	/**
	 * Create a new ExpressionBuilder
	 * 
	 * @param expression
	 *            the expression to evaluate
	 */
	public ExpressionBuilder(String expression) {
		this.expression = expression;
	}

	/**
	 * set the variable's names used in the expression without setting their
	 * values
	 * 
	 * @param variableNames
	 *            vararg {@link String} of the variable's names used in the
	 *            expression
	 * @return
	 */
	public ExpressionBuilder withVariableNames(String... variableNames) {
		for (String variable : variableNames) {
			variables.put(variable, null);
		}
		return this;
	}

	/**
	 * set the value for a variable
	 * 
	 * @param variableName
	 * @param value
	 * @return the {@link ExpressionBuilder} instance
	 */
	public ExpressionBuilder withVariable(String variableName, double value) {
		variables.put(variableName, value);
		return this;
	}

	/**
	 * set the values for variables
	 * 
	 * @param variableMap
	 *            a map of variable names to variable values
	 * @return the {@link ExpressionBuilder} instance
	 */
	public ExpressionBuilder withVariables(Map<String, Double> variableMap) {
		for (Entry<String, Double> v : variableMap.entrySet()) {
			variables.put(v.getKey(), v.getValue());
		}
		return this;
	}

	/**
	 * add a custom function instance for the evaluator to recognize
	 * 
	 * @param function
	 *            the {@link CustomFunction} to add
	 * @return the {@link ExpressionBuilder} instance
	 */
	public ExpressionBuilder withCustomFunction(CustomFunction function) {
		customFunctions.add(function);
		return this;
	}
	
	public ExpressionBuilder withCustomFunctions(Collection<CustomFunction> functions){
		customFunctions.addAll(functions);
		return this;
	}

	/**
	 * build a new {@link Calculatable} from the expression using the supplied
	 * variables
	 * 
	 * @return the {@link Calculatable} which can be used to evaluate the
	 *         expression
	 * @throws UnknownFunctionException
	 *             when an unrecognized function name is used in the expression
	 * @throws UnparseableExpressionException
	 *             if the expression could not be parsed
	 */
	public Calculatable build() throws UnknownFunctionException, UnparseableExpressionException {
		if (expression.indexOf('=') == -1 && !variables.isEmpty()) {

			// User supplied an expression without leading "f(...)="
			// so we just append the user function to a proper "f()="
			// for PostfixExpression.fromInfix()
			StringBuilder function = new StringBuilder("f(");
			for (String var : variables.keySet()) {
				function.append(var + ",");
			}
			expression = function.deleteCharAt(function.length() - 1).toString() + ")=" + expression;
		}
		// create the PostfixExpression and return it as a Calculatable
		PostfixExpression delegate = PostfixExpression.fromInfix(expression, customFunctions);
		for (String var : variables.keySet()) {
			if (variables.get(var) != null) {
				delegate.setVariable(var, variables.get(var));
			}
		}
		return delegate;
	}
}