package org.aksw.limes.core.execution.rewriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.aksw.limes.core.io.ls.LinkSpecification;
import org.aksw.limes.core.io.parser.Parser;
import org.apache.log4j.Logger;
import org.matheclipse.core.eval.EvalUtilities;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.form.output.OutputFormFactory;
import org.matheclipse.core.form.output.StringBufferWriter;
import org.matheclipse.core.interfaces.IExpr;

public class DefaultRewriter implements IRewriter {

    static Logger logger = Logger.getLogger("LIMES");

    @Override
    public LinkSpecification rewrite(LinkSpecification spec) {
	return spec;
    }

    /**
     * Returns the factor expression of a metric 
     *
     * @param metric,
     *            input metric expression
     * @return metric, factor expression as string
     */
    public static String factorExpression(String metric) {
	try {
	    String copy = metric.replaceAll(" ", "");
	    if (isBoolean(metric)) {
		List<String> variables = new ArrayList<String>();
		variables.addAll(getVariables(metric));
		copy = getInfix(copy);
		for (int i = 0; i < variables.size(); i++) {
		    copy = copy.replaceAll(Pattern.quote(variables.get(i)), ((char) ('A' + i)) + "");
		}

		// factorize
		F.initSymbols(null);
		EvalUtilities util = new EvalUtilities();
		IExpr result;
		String input = "Factor[" + copy + "]";
		input = "Factor[((B+A)*(B+A))]";
		result = util.evaluate(input);
		StringBufferWriter buf = new StringBufferWriter();
		OutputFormFactory.get().convert(buf, result);
		copy = buf.toString();
		// replace back
		for (int i = 0; i < variables.size(); i++) {
		    copy = copy.replaceAll(Pattern.quote((char) ('A' + i) + ""), variables.get(i));
		}
		return copy;
	    }
	} catch (Exception e) {
	    e.printStackTrace();
	    logger.warn("Error factoring expression " + metric);
	}
	return metric;
    }
    /**
     * Returns infix of a metric expression
     *
     * @param metric,
     *            input metric expression
     * @return metric, expression with infix as string
     */
    public static String getInfix(String metric) {
	try {
	    Parser p = new Parser(metric, 1d);
	    if (p.isAtomic()) {
		return metric;
	    }
	    String operation = p.getOperation();
	    String term1 = p.getTerm1();
	    String t1 = p.threshold1 + "";
	    String term2 = p.getTerm2();
	    String t2 = p.threshold2 + "";

	    if (operation.equals("MIN") || operation.equals("AND")) {
		return "(" + getInfix(p.getTerm1() + "|" + t1) + "*" + getInfix(p.getTerm2() + "|" + t2) + ")";
	    }
	    if (operation.equals("MAX") || operation.equals("OR")) {
		return "(" + getInfix(p.getTerm1() + "|" + t1) + "+" + getInfix(p.getTerm2() + "|" + t2) + ")";
	    } else {
		return metric;
	    }
	} catch (Exception e) {
	    logger.warn("Error parsing " + metric);
	    e.printStackTrace();
	}
	return metric;
    }

    /**
     * Collects all variables of a measure
     *
     * @param metric
     *            Measure to analyse
     * @return Set of variables contained in the measure
     */
    public static Set<String> getVariables(String metric) {
	Parser p = new Parser(metric, 1d);
	Set<String> result;
	if (p.isAtomic()) {
	    result = new HashSet<String>();
	    result.add(metric);
	} else {
	    result = getVariables(p.getTerm1() + "|" + p.threshold1);
	    result.addAll(getVariables(p.getTerm2() + "|" + p.threshold2));
	}
	return result;
    }

    /**
     * Checks whether the metric is made of ANDs and ORs
     *
     * @param metric
     * @return
     */
    public static boolean isBoolean(String metric) {
	Parser p = new Parser(metric, 1d);
	if (p.isAtomic()) {
	    return true;
	}
	String term1 = p.getTerm1();
	String term2 = p.getTerm2();
	if (!(p.getOperation().equals("ADD") && p.getOperation().equals("MULT"))) {
	    return (isBoolean(term1) && isBoolean(term2));
	} else {
	    return false;
	}
    }

    
    
    
}