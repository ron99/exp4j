/* 
 * Copyright 2014 Frank Asseg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package net.objecthunter.exp4j;

import java.util.*;
import java.util.concurrent.*;

import net.objecthunter.exp4j.function.*;
import net.objecthunter.exp4j.operator.*;
import net.objecthunter.exp4j.tokenizer.*;

public class Expression {

    private final Token[] tokens;

    private final Map<String, Double> variables;

    private final Set<String> userFunctionNames;

    private static Map<String, Double> createDefaultVariables() {
        final Map<String, Double> vars = new HashMap<String, Double>(4);
        vars.put("pi", Math.PI);
        vars.put("π", Math.PI);
        vars.put("φ", 1.61803398874d);
        vars.put("e", Math.E);
        return vars;
    }
    
    /**
     * Creates a new expression that is a copy of the existing one.
     * 
     * @param existing the expression to copy
     */
    public Expression(final Expression existing) {
    	this.tokens = Arrays.copyOf(existing.tokens, existing.tokens.length);
    	this.variables = new HashMap<String,Double>();
    	this.variables.putAll(existing.variables);
    	this.userFunctionNames = new HashSet<String>(existing.userFunctionNames);
    }

    Expression(final Token[] tokens) {
        this.tokens = tokens;
        this.variables = createDefaultVariables();
        this.userFunctionNames = Collections.<String>emptySet();
    }

    Expression(final Token[] tokens, Set<String> userFunctionNames) {
        this.tokens = tokens;
        this.variables = createDefaultVariables();
        this.userFunctionNames = userFunctionNames;
    }

    public Expression setVariable(final String name, final double value) {
        this.checkVariableName(name);
        this.variables.put(name, value);
        return this;
    }

    private void checkVariableName(String name) {
        if (this.userFunctionNames.contains(name) || Functions.getBuiltinFunction(name) != null) {
            throw new IllegalArgumentException("The variable name '" + name + "' is invalid. Since there exists a function with the same name");
        }
    }

    public Expression setVariables(Map<String, Double> variables) {
        for (Map.Entry<String, Double> v : variables.entrySet()) {
            this.setVariable(v.getKey(), v.getValue());
        }
        return this;
    }

    public ValidationResult validate(boolean checkVariablesSet) {
        final List<String> errors = new ArrayList<String>(0);
        if (checkVariablesSet) {
            /* check that all vars have a value set */
            for (final Token t : this.tokens) {
                if (t.getType() == Token.TOKEN_VARIABLE) {
                    final String var = ((VariableToken) t).getName();
                    if (!variables.containsKey(var)) {
                        errors.add("The setVariable '" + var + "' has not been set");
                    }
                }
            }
        }

        /* Check if the number of operands, functions and operators match.
           The idea is to increment a counter for operands and decrease it for operators.
           When a function occurs the number of available arguments has to be greater
           than or equals to the function's expected number of arguments.
           The count has to be larger than 1 at all times and exactly 1 after all tokens
           have been processed */
        int count = 0;
        for (Token tok : this.tokens) {
            switch (tok.getType()) {
                case Token.TOKEN_NUMBER:
                case Token.TOKEN_VARIABLE:
                    count++;
                    break;
                case Token.TOKEN_FUNCTION:
                    final Function func = ((FunctionToken) tok).getFunction();
                    final int argsNum = func.getNumArguments(); 
                    if (argsNum > count) {
                        errors.add("Not enough arguments for '" + func.getName() + "'");
                    }
                    if (argsNum > 1) {
                        count -= argsNum - 1;
                    }
                    break;
                case Token.TOKEN_OPERATOR:
                    Operator op = ((OperatorToken) tok).getOperator();
                    if (op.getNumOperands() == 2) {
                        count--;
                    }
                    break;
            }
            if (count < 1) {
                errors.add("Too many operators");
                return new ValidationResult(false, errors);
            }
        }
        if (count > 1) {
            errors.add("Too many operands");
        }
        return errors.size() == 0 ? ValidationResult.SUCCESS : new ValidationResult(false, errors);

    }

    public ValidationResult validate() {
        return validate(true);
    }

    public Future<Double> evaluateAsync(ExecutorService executor) {
        return executor.submit(new Callable<Double>() {
            @Override
            public Double call() throws Exception {
                return evaluate();
            }
        });
    }

    public double evaluate() {
        final ArrayStack output = new ArrayStack();
        for (int i = 0; i < tokens.length; i++) {
            Token t = tokens[i];
            if (t.getType() == Token.TOKEN_NUMBER) {
                output.push(((NumberToken) t).getValue());
            } else if (t.getType() == Token.TOKEN_VARIABLE) {
                final String name = ((VariableToken) t).getName();
                final Double value = this.variables.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("No value has been set for the setVariable '" + name + "'.");
                }
                output.push(value);
            } else if (t.getType() == Token.TOKEN_OPERATOR) {
                OperatorToken op = (OperatorToken) t;
                if (output.size() < op.getOperator().getNumOperands()) {
                    throw new IllegalArgumentException("Invalid number of operands available for '" + op.getOperator().getSymbol() + "' operator");
                }
                if (op.getOperator().getNumOperands() == 2) {
                    /* pop the operands and push the result of the operation */
                    double rightArg = output.pop();
                    double leftArg = output.pop();
                    output.push(op.getOperator().apply(leftArg, rightArg));
                } else if (op.getOperator().getNumOperands() == 1) {
                    /* pop the operand and push the result of the operation */
                    double arg = output.pop();
                    output.push(op.getOperator().apply(arg));
                }
            } else if (t.getType() == Token.TOKEN_FUNCTION) {
                FunctionToken func = (FunctionToken) t;
                if (output.size() < func.getFunction().getNumArguments()) {
                    throw new IllegalArgumentException("Invalid number of arguments available for '" + func.getFunction().getName() + "' function");
                }
                /* collect the arguments from the stack */
                double[] args = new double[func.getFunction().getNumArguments()];
                for (int j = 0; j < func.getFunction().getNumArguments(); j++) {
                    args[j] = output.pop();
                }
                output.push(func.getFunction().apply(this.reverseInPlace(args)));
            }
        }
        if (output.size() > 1) {
            throw new IllegalArgumentException("Invalid number of items on the output queue. Might be caused by an invalid number of arguments for a function.");
        }
        return output.pop();
    }

    private double[] reverseInPlace(double[] args) {
        int len = args.length;
        for (int i = 0; i < len / 2; i++) {
            double tmp = args[i];
            args[i] = args[len - i - 1];
            args[len - i - 1] = tmp;
        }
        return args;
    }
    
    
    /**
     * Creates derivative <code>Expression</code> to this  <code>Expression</code> with respect to the given variable.
     * <p>Current support only for the built-in operators and functions</p>
     * 
     * @param variable the variable which the  <code>Expression</code> is derivative by (all other variables will be treated as constants)
     * @return the derivative  <code>Expression</code> to this  <code>Expression</code>
     * @throws IllegalArgumentException if the  <code>Expression</code> or the given variable are not valid
     * @throws UnsupportedOperationException if the  <code>Expression</code> contains not built-in operator or function
     */
    public Expression derivative(final String variable) throws IllegalArgumentException, UnsupportedOperationException {
    	ValidationResult validation = this.validate(false);
    	if(!validation.isValid())
    		throw new IllegalArgumentException("The expression is invalid for the following reasons: "+validation.getErrors());
    	
    	if(variable.matches("π|pi|e|φ"))
    		throw new IllegalArgumentException("Cannot derivative by the variable '"+variable+"' because it's a constant");
    	
    	
		List<Token> tokens = new ArrayList<Token>(Arrays.asList(this.tokens));
		
		Expression derivative = new Expression(derivative(tokens, variable).toArray(new Token[0]));
		derivative.setVariables(this.variables);
		return derivative;
	}
    
    private List<Token> derivative(List<Token> tokens, final String var) {
		Token token = tokens.get(tokens.size()-1);
		
		switch (token.getType()){
			case Token.TOKEN_OPERATOR:
				List<List<Token>> args = getTokensArguments(tokens.subList(0, tokens.size()-1), ((OperatorToken) token).getOperator().getNumOperands());
				return derivative(((OperatorToken) token).getOperator(), args.get(0), args.get(1), var);
				
			case Token.TOKEN_FUNCTION: 
				return derivative(((FunctionToken) token).getFunction(), tokens.subList(0, tokens.size()-1), var);
				
			case Token.TOKEN_VARIABLE:
				if(((VariableToken) token).getName().equals(var))
					return new ArrayList<Token>(Arrays.asList((Token) new NumberToken(1d)));
				
			case Token.TOKEN_NUMBER:
				return new ArrayList<Token>(Arrays.asList((Token) new NumberToken(0d)));
				
			default:
				throw new UnsupportedOperationException("The token type '"+token.getClass().getName()+"' is not derivative supported yet");
		}
	}
    
    private List<Token> derivative(Operator operator, List<Token> leftTokens, List<Token> rightTokens, final String var) {
    	List<Token> dTokens = new ArrayList<Token>();
    	
	    switch (operator.getSymbol()) {
			case "+":
				dTokens.addAll(derivative(leftTokens, var));
				if(operator.getNumOperands() == 2) {
					dTokens.addAll(derivative(rightTokens, var));
				}
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('+', operator.getNumOperands())));
				break;
				
			case "-":
				dTokens.addAll(derivative(leftTokens, var));
				if(operator.getNumOperands() == 2) {
					dTokens.addAll(derivative(rightTokens, var));
				}
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('-', operator.getNumOperands())));
				break;
				
			case "*":
				dTokens.addAll(derivative(leftTokens, var));
				dTokens.addAll(rightTokens);
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.addAll(leftTokens);
				dTokens.addAll(derivative(rightTokens, var));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('+', 2)));
				break;
			
			case "/":
				dTokens.addAll(derivative(leftTokens, var));
				dTokens.addAll(rightTokens);
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.addAll(leftTokens);
				dTokens.addAll(derivative(rightTokens, var));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 2)));
				dTokens.addAll(rightTokens);
				dTokens.add(new NumberToken(2d));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('^', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('/', 2)));
				break;
				
			case "^":
				dTokens.addAll(leftTokens);
				dTokens.addAll(rightTokens);
				dTokens.add(new NumberToken(1d));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('^', 2)));
				dTokens.addAll(rightTokens);
				dTokens.addAll(derivative(leftTokens, var));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.addAll(leftTokens);
				dTokens.addAll(leftTokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("log")));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.addAll(derivative(rightTokens, var));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('+', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				break;
			
			case "%":
				dTokens.addAll(leftTokens);
				dTokens.addAll(rightTokens);
				dTokens.addAll(leftTokens);
				dTokens.addAll(rightTokens);
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('/', 2)));
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("floor")));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 2)));
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("abs")));
				dTokens.addAll(leftTokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("signum")));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
				return derivative(dTokens, var);
				
			default:
				throw new UnsupportedOperationException("The operator '"+operator.getSymbol()+"' is not derivative supported yet");
    	}
	    
	    return dTokens;
	}
    
    private List<Token> derivative(Function function, List<Token> tokens, final String var) {
    	List<Token> dTokens = derivative(tokens, var);
    	
		switch (function.getName()) {
			case "sqrt":
			case "cbrt":
			case "exp":
			case "expm1":
				switch (function.getName()) {
					case "sqrt":
						tokens.add(new NumberToken(1d/2));
						break;
						
					case "cbrt":
						tokens.add(new NumberToken(1d/3));
						break;
						
					case "exp":
					case "expm1":
						tokens.add(0, new VariableToken("e"));
						break;
				}
			case "pow":
				tokens.add(new OperatorToken(Operators.getBuiltinOperator('^', 2)));
				return derivative(tokens, var);
		
			case "sin":
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("cos")));
				break;
				
			case "cos":
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("sin")));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 1)));
				break;
				
			case "tan":
				dTokens.add(new NumberToken(1d));
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("cos")));
				dTokens.add(new NumberToken(2d));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('^', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('/', 2)));
				break;
				
			case "asin":
				dTokens.add(new NumberToken(1d));
				dTokens.add(new NumberToken(1d));
				dTokens.addAll(tokens);
				dTokens.add(new NumberToken(2d));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('^', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 2)));
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("sqrt")));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('/', 2)));
				break;
				
			case "acos":
				dTokens.addAll(derivative(new FunctionToken(Functions.getBuiltinFunction("asin")).getFunction(), tokens, var));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 1)));
				break;
				
			case "atan":
				dTokens.add(new NumberToken(1d));
				dTokens.addAll(tokens);
				dTokens.add(new NumberToken(2d));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('^', 2)));
				dTokens.add(new NumberToken(1d));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('+', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('/', 2)));
				break;
				
			case "sinh":
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("cosh")));
				break;
				
			case "cosh":
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("sinh")));
				break;
				
			case "tanh":
				dTokens.add(new NumberToken(1d));
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("cosh")));
				dTokens.add(new NumberToken(2d));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('^', 2)));
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('/', 2)));
				break;
				
			case "log":
			case "log2":
			case "log10":
			case "log1p":
				dTokens.add(new NumberToken(1d));
				dTokens.addAll(tokens);
				switch (function.getName()) {
					case "log2":
						dTokens.add(new NumberToken(Math.log(2)));
						dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
						
					case "log10":
						dTokens.add(new NumberToken(Math.log(10)));
						dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
						
					case "log1p":
						dTokens.add(new NumberToken(1d));
						dTokens.add(new OperatorToken(Operators.getBuiltinOperator('+', 2)));
				}
				dTokens.add(new OperatorToken(Operators.getBuiltinOperator('/', 2)));
				break;
			
			case "abs":
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(Functions.getBuiltinFunction("signum")));
				break;
				
			case "signum":
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(new Function("delta") {
					
					@Override
					public double apply(double... args) {
						if(args[0] != 0d)
							return 0d;
						else
							return Double.POSITIVE_INFINITY;
					}
				}));
				break;
				
			case "floor":
				dTokens.addAll(tokens);
				dTokens.add(new FunctionToken(new Function("floorDerivative") {
					
					@Override
					public double apply(double... args) {
						if(args[0] != (int)args[0])
							return 0d;
						else
							return Double.NaN;
					}
				}));
				break;
			
			case "ceil":
				tokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 1)));
				tokens.add(new FunctionToken(Functions.getBuiltinFunction("floor")));
				tokens.add(new OperatorToken(Operators.getBuiltinOperator('-', 1)));
				return derivative(tokens, var);
				
			default:
				throw new UnsupportedOperationException("The function '"+function.getName()+"' is not derivative supported");
		}
		
		dTokens.add(new OperatorToken(Operators.getBuiltinOperator('*', 2)));
		return dTokens;
	}

    
    private List<List<Token>> getTokensArguments(List<Token> tokens, int numOperands) {
    	List<List<Token>> tArgs = new ArrayList<List<Token>>(2);
    	if(numOperands == 1) {
    		tArgs.add(tokens);
	        tArgs.add(new ArrayList<Token>(0));
    	}
    	else {
	    	int last = 0;
			final ArrayStack output = new ArrayStack();
	        for (int i = 0; i < tokens.size()-1; i++) {
	            Token t = tokens.get(i);
	            switch (t.getType()) {
		            case Token.TOKEN_NUMBER:
		                output.push(((NumberToken) t).getValue());
		                break;
		                
		            case Token.TOKEN_VARIABLE:
		                output.push(1d);
		                break;
		                
		            case Token.TOKEN_OPERATOR:
		                Operator operator = ((OperatorToken) t).getOperator();
		                if (operator.getNumOperands() == 2) 
		                    output.push(operator.apply(output.pop(), output.pop()));
		                else if (operator.getNumOperands() == 1) 
		                    output.push(operator.apply(output.pop()));
		                break;
		                
		            case Token.TOKEN_FUNCTION:
		                FunctionToken func = (FunctionToken) t;
		                double[] args = new double[func.getFunction().getNumArguments()];
		                for (int j = 0; j < func.getFunction().getNumArguments(); j++) {
		                    args[j] = output.pop();
		                }
		                output.push(func.getFunction().apply(this.reverseInPlace(args)));
		                break;
		        }
	            if(output.size() == 1) {
	            	last = i;
	            }
	        }
	        
	        tArgs.add(tokens.subList(0, last+1));
	        tArgs.add(tokens.subList(last+1, tokens.size()));
    	}
    	
    	return tArgs;
	}
    
}