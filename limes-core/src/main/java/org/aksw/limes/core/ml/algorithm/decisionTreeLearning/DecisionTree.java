package org.aksw.limes.core.ml.algorithm.decisionTreeLearning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.aksw.limes.core.datastrutures.GoldStandard;
import org.aksw.limes.core.datastrutures.PairSimilar;
import org.aksw.limes.core.evaluation.evaluationDataLoader.DataSetChooser;
import org.aksw.limes.core.evaluation.evaluationDataLoader.EvaluationData;
import org.aksw.limes.core.evaluation.qualititativeMeasures.FMeasure;
import org.aksw.limes.core.evaluation.qualititativeMeasures.PseudoFMeasure;
import org.aksw.limes.core.evaluation.qualititativeMeasures.PseudoRefFMeasure;
import org.aksw.limes.core.exceptions.UnsupportedMLImplementationException;
import org.aksw.limes.core.execution.engine.ExecutionEngine;
import org.aksw.limes.core.execution.engine.ExecutionEngineFactory;
import org.aksw.limes.core.execution.engine.ExecutionEngineFactory.ExecutionEngineType;
import org.aksw.limes.core.execution.engine.SimpleExecutionEngine;
import org.aksw.limes.core.execution.planning.plan.Instruction;
import org.aksw.limes.core.execution.planning.plan.Plan;
import org.aksw.limes.core.execution.planning.planner.DynamicPlanner;
import org.aksw.limes.core.execution.rewriter.Rewriter;
import org.aksw.limes.core.execution.rewriter.RewriterFactory;
import org.aksw.limes.core.io.cache.ACache;
import org.aksw.limes.core.io.cache.MemoryCache;
import org.aksw.limes.core.io.config.Configuration;
import org.aksw.limes.core.io.ls.LinkSpecification;
import org.aksw.limes.core.io.mapping.AMapping;
import org.aksw.limes.core.io.mapping.MappingFactory;
import org.aksw.limes.core.measures.mapper.MappingOperations;
import org.aksw.limes.core.ml.algorithm.AMLAlgorithm;
import org.aksw.limes.core.ml.algorithm.MLAlgorithmFactory;
import org.aksw.limes.core.ml.algorithm.MLImplementationType;
import org.aksw.limes.core.ml.algorithm.MLResults;
import org.aksw.limes.core.ml.algorithm.classifier.ExtendedClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecisionTree {
	protected static Logger logger = LoggerFactory.getLogger(DecisionTree.class);

	private DecisionTreeLearning dtl;
	private static HashMap<String, AMapping> calculatedMappings = new HashMap<String, AMapping>();
	private static HashMap<String, AMapping> pathMappings = new HashMap<String, AMapping>();
	private static double totalFMeasure = 0.0;
	public static int maxDepth = 0;
	private static String spaceChar = "︴";

	private ACache sourceCache;
	private ACache targetCache;
	private ACache testSourceCache;
	private ACache testTargetCache;
	private AMapping parentMapping;
	private ExtendedClassifier classifier;
	private DecisionTree parent;
	private DecisionTree leftChild;
	private DecisionTree rightChild;
	private boolean root = false;
	private boolean isLeftNode = false;
	private boolean checked = false;
	private PseudoFMeasure pseudoFMeasure;
	private int depth;

	private double minPropertyCoverage;
	private double propertyLearningRate;

	public static boolean isSupervised = false;
	private AMapping refMapping;

	public static AMapping actualRefMapping;

	public DecisionTree(DecisionTreeLearning dtl, ACache sourceCache, ACache targetCache, PseudoFMeasure pseudoFMeasure,
			double minPropertyCoverage, double propertyLearningRate, AMapping refMapping) {
		calculatedMappings = new HashMap<String, AMapping>();
		pathMappings = new HashMap<String, AMapping>();
		totalFMeasure = 0.0;
		this.dtl = dtl;
		this.sourceCache = sourceCache;
		this.targetCache = targetCache;
		this.pseudoFMeasure = pseudoFMeasure;
		this.minPropertyCoverage = minPropertyCoverage;
		this.propertyLearningRate = propertyLearningRate;
		root = true;
		depth = 0;
		this.refMapping = refMapping;
		buildTestCaches();
	}

	private DecisionTree(DecisionTreeLearning dtl, ACache originalSourceCache, ACache originalTargetCache,
			AMapping parentMapping, PseudoFMeasure pseudoFMeasure, double minPropertyCoverage,
			double propertyLearningRate, DecisionTree parent, boolean isLeftNode, AMapping refMapping) {
		this.dtl = dtl;
		this.sourceCache = originalSourceCache;
		this.targetCache = originalTargetCache;
		this.parentMapping = parentMapping;
		this.pseudoFMeasure = pseudoFMeasure;
		this.minPropertyCoverage = minPropertyCoverage;
		this.propertyLearningRate = propertyLearningRate;
		this.parent = parent;
		this.isLeftNode = isLeftNode;
		this.root = false;
		if (parent != null)
			this.depth = this.parent.depth + 1;
		this.refMapping = refMapping;
		buildTestCaches();
	}

	private void buildTestCaches() {
		if (refMapping == null)
			return;
		testSourceCache = new MemoryCache();
		testTargetCache = new MemoryCache();
		for (String s : refMapping.getMap().keySet()) {
			testSourceCache.addInstance(sourceCache.getInstance(s).copy());
			for (String t : refMapping.getMap().get(s).keySet()) {
				testTargetCache.addInstance(targetCache.getInstance(t).copy());
			}
		}
	}

	public DecisionTree buildTree(int maxDepth) {
		List<ExtendedClassifier> classifiers = findClassifiers();
		if (classifiers.size() == 0) {
			return null;
		}
		Collections.sort(classifiers, Collections.reverseOrder());
		classifier = classifiers.get(0);

		if (root) {
			totalFMeasure = classifier.getfMeasure();
		} else {
			if (classifier.getfMeasure() <= 0.1) {
				return null;
			}
		}
		if (maxDepth != this.depth) {
			rightChild = new DecisionTree(dtl, sourceCache, targetCache, classifier.getMapping(), pseudoFMeasure,
					minPropertyCoverage, propertyLearningRate, this, false, refMapping);
			rightChild = rightChild.buildTree(maxDepth);
			leftChild = new DecisionTree(dtl, sourceCache, targetCache, classifier.getMapping(), pseudoFMeasure,
					minPropertyCoverage, propertyLearningRate, this, true, refMapping);
			leftChild = leftChild.buildTree(maxDepth);
		}
		return this;
	}

	// private AMapping getNewRefMapping(AMapping classifierMapping, AMapping
	// refMapping, boolean left) {
	// AMapping res = MappingFactory.createDefaultMapping();
	// if (left) {
	// res = MappingOperations.difference(refMapping, classifierMapping);
	// } else {
	// res = MappingOperations.intersection(classifierMapping, refMapping);
	// }
	// return res;
	// }

	public DecisionTree prune() {
		int currentDepth = maxDepth;
		while (currentDepth >= 0) {
			getRootNode().prune(currentDepth);
			currentDepth--;
		}
		return this;
	}

	private DecisionTree prune(int depth) {
		if (rightChild == null && leftChild == null) {
			return this;
		}
		// Go to the leaves
		if (rightChild != null && !rightChild.checked) {
			rightChild.prune(depth);
		}
		if (leftChild != null && !leftChild.checked) {
			leftChild.prune(depth);
		}
		if (this.depth != depth) {
			return this;
		}

		boolean deleteLeft = false;
		boolean deleteRight = false;

		DecisionTree tmpRightChild = rightChild;
		DecisionTree tmpLeftChild = leftChild;
		this.rightChild = null;
		double tmp = 0.0;
		tmp = getTotalPseudoFMeasure();
		if (tmp >= totalFMeasure) {
			deleteRight = true;
		}
		this.rightChild = tmpRightChild;
		this.leftChild = null;
		tmp = getTotalPseudoFMeasure();
		if (tmp >= totalFMeasure) {
			totalFMeasure = tmp;
			deleteLeft = true;
			deleteRight = false;
		}
		this.rightChild = null;
		this.leftChild = null;
		tmp = getTotalPseudoFMeasure();
		if (tmp >= totalFMeasure) {
			totalFMeasure = tmp;
			deleteLeft = true;
			deleteRight = true;
		}
		if (!deleteLeft) {
			this.leftChild = tmpLeftChild;
		}
		if (!deleteRight) {
			this.rightChild = tmpRightChild;
		}
		return this;
	}

	public double getTotalPseudoFMeasure() {
		DecisionTree rootNode = getRootNode();
//		LinkSpecification ls = getTotalLS();
//		AMapping prediction = rootNode.dtl.predict(rootNode.testSourceCache, rootNode.testTargetCache,
//				new MLResults(ls, null, -1.0, null));
//		double pres = rootNode.calculateFMeasure(prediction, rootNode.refMapping);
		AMapping pathMapping = getTotalMapping();
//		System.out.println("PRed size: " + prediction.size() + "path size: " + pathMapping.size());
//		boolean same = prediction.size() == pathMapping.size();
//		if(!same){
//			System.err.println("\n DIFF \n");
//			System.out.println(getRootNode().toStringPretty());
//			System.out.println(ls.toStringPretty());
//			for(String s: pathMappings.keySet()){
//				System.out.println(s);
//			}
//		}
//		System.out.println("Mappings equals: " + same);
		double res = rootNode.calculateFMeasure(pathMapping, rootNode.refMapping);
		// double resT = -1.0;
		// if(actualRefMapping != null){
		// resT = rootNode.calculateFMeasure(
		// rootNode.dtl.predict(rootNode.sourceCache, rootNode.targetCache,
		// new MLResults(rootNode.dtl.tp.parseTreePrefix(rootNode.toString()),
		// null, -1.0, null)),
		// actualRefMapping);
		// }
		// System.out.println("test: " +res + " actual: " +resT);
//		System.out.println("predict: " + pres + " path: " + res);
		return res;
		// AMapping totalMapping = getTotalMapping(root);
		// double pf = root.calculateFMeasure(totalMapping, root.refMapping);
		// pathMappings = new HashMap<String, AMapping>();
		// return pf;
	}

	private DecisionTree getRootNode() {
		if (!root) {
			if (parent == null) {
				logger.error("Detached node!Cannot get root! Returning null!");
				return null;
			}
			return parent.getRootNode();
		}
		return this;
	}

	public AMapping getTotalMapping() {
		pathMappings = new HashMap<String, AMapping>();
		DecisionTree rootNode = getRootNode();
		calculatePathMappings(rootNode);
		AMapping res = MappingFactory.createDefaultMapping();
		for (String s : pathMappings.keySet()) {
			res = MappingOperations.union(pathMappings.get(s), res);
		}
		return res;
	}

	private void calculatePathMappings(DecisionTree node) {
		if (node.rightChild == null && node.leftChild == null) {
			if (node.root) {
				AMapping res = node.classifier.getMapping();
				pathMappings.put(node.getPathString(), res);
			} else {
				putPathMappingsLeaf(node);
			}
		} else if (node.rightChild != null && node.leftChild == null) {
			calculatePathMappings(node.rightChild);
		} else if (node.leftChild != null && node.rightChild == null) {
			if (!root) {
				putPathMappingsLeaf(node);
			}
			calculatePathMappings(node.leftChild);
		} else {
			calculatePathMappings(node.rightChild);
			calculatePathMappings(node.leftChild);
		}
	}

	private void putPathMappingsLeaf(DecisionTree node) {
			pathMappings.put(node.getPathString(), node.getPathMapping());
	}
	
	private AMapping getPathMapping(){
		if(root){
			return classifier.getMapping();
		}
		AMapping parentMapping = this.parent.getPathMapping();
		AMapping nodeMapping = classifier.getMapping();
		AMapping res = null;
		if (isLeftNode) {
			res = MappingOperations.difference(nodeMapping, parentMapping);
		} else {
			res = MappingOperations.intersection(nodeMapping, parentMapping);
		}
		return res;
	}

	private String getPathString() {
		String str = "" + depth;
		if (root) {
			if (this.classifier.getMetricExpression() != null && !this.classifier.getMetricExpression().equals("")) {
				str += spaceChar + this.classifier.getMetricExpression();
			}
		} else {
			if (isLeftNode) {
				str += "left";
			} else {
				str += "right";
			}
			str += parent.getPathString() + spaceChar + "³" + isLeftNode + "³" + this.classifier.getMetricExpression();
		}
		return str;
	}

	public LinkSpecification getTotalLS() {
		pathMappings = new HashMap<String, AMapping>();
		calculatePathMappings(getRootNode());
		final String left = "³true³";
		final String right = "³false³";
		String[] pathLS = new String[pathMappings.keySet().size()];
		int countPathLS = 0;
		for (String s : pathMappings.keySet()) {
			double outerThreshold = 0.0;
			String[] path = s.split(spaceChar);
			String lsString = "";
			for (int i = 1; i < path.length; i++) {
				if (path[i].startsWith(left)) {
//					if(lsString.equals("")){
//                        lsString = "MINUS(" + path[i] + "," + path[i - 1] + ")";
//                        i--;
//					}else{
						lsString = "MINUS(" + path[i] + "," + lsString +")";
//					}
					lsString += "|0.0";
				} else if (path[i].startsWith(right)) {
//					if(lsString.equals("")){
//                        lsString = "AND(" + path[i - 1] + "," + path[i] + ")";
//                        i--;
//					}else{
						lsString = "AND(" + path[i] + "," + lsString +")";
//					}
					lsString += "|0.0";
				} else {
					if(lsString.equals("")){
						//path[0] is always the shortcode for the path e.g. 2left1right0
						if(path.length == 2){
						//Split on | because this an atomic ls and we use the rest as threshold
                            if(path[i].contains("|")){
                                outerThreshold = Double.parseDouble(path[i].split("\\|")[1]);
                            }
						}
                        lsString = path[i];
					}else{
						if(path[i+1].startsWith(right)){
                            lsString = "AND(" + path[i+1] + "," + path[i] +")";
						}else{
                            lsString = "MINUS(" + path[i+1] + "," + path[i] +")";
						}
					}
				}
			}
			lsString = lsString.replaceAll(left, "");
			lsString = lsString.replaceAll(right, "");
			LinkSpecification ls = new LinkSpecification(lsString, outerThreshold);
			AMapping predMapping = dtl.predict(testSourceCache, testTargetCache, new MLResults(ls,null, 0, null));
			AMapping pathMapping = pathMappings.get(s);
			boolean same = predMapping.equals(pathMapping);
			if(!same){
				System.err.println("\n\n ====== ! ! ! ! ! DIFFERENT !!!!!! ====== \n\n");
			}
			lsString += "|" + outerThreshold;
			pathLS[countPathLS] = lsString;
			countPathLS++;
		}
		String finalLSString = "";
		LinkSpecification finalLS = null;
		if(pathLS.length==1){
			//Split on last | to get threshold
			int index = pathLS[0].lastIndexOf("|");
			String[] lsStringArr = {pathLS[0].substring(0, index), pathLS[0].substring(index + 1)};
			return new LinkSpecification(lsStringArr[0],Double.parseDouble(lsStringArr[1]));
		}
		for(int i = 0; i < pathLS.length; i++){
			if(i == 0){
				finalLSString += "OR(" + pathLS[i] + "," + pathLS[i+1] + ")";
				i++;
			}else{
				finalLSString = "OR(" + finalLSString + "," + pathLS[i] +")";
			}
			if(i == (pathLS.length - 1)){
				finalLS = new LinkSpecification(finalLSString, 0.0);
			}else{
				finalLSString += "|0.0";
			}
		}
		return finalLS;
	}

	@Override
	public DecisionTree clone() {
		DecisionTree cloned = null;
		if (this.root) {
			cloned = new DecisionTree(dtl, sourceCache, targetCache, pseudoFMeasure, minPropertyCoverage,
					propertyLearningRate, refMapping);
			cloned.classifier = new ExtendedClassifier(classifier.getMeasure(), classifier.getThreshold(),
					classifier.getSourceProperty(), classifier.getTargetProperty());
			cloned.checked = checked;
			cloned.depth = depth;
			if (rightChild != null) {
				cloned.rightChild = this.rightChild.cloneWithoutParent();
			}
			if (leftChild != null) {
				cloned.leftChild = this.leftChild.cloneWithoutParent();
			}
		} else {
			DecisionTree parentClone = this.parent.cloneWithoutChild(isLeftNode);
			cloned = this.cloneWithoutParent();
			if (this.isLeftNode) {
				parentClone.leftChild = cloned;
				parentClone.rightChild = this.parent.rightChild.cloneWithoutParent();
			} else {
				parentClone.rightChild = cloned;
				parentClone.leftChild = this.parent.leftChild.cloneWithoutParent();
			}
		}
		return cloned;
	}

	protected DecisionTree cloneWithoutParent() {
		DecisionTree cloned = null;
		if (root) {
			cloned = new DecisionTree(dtl, sourceCache, targetCache, pseudoFMeasure, minPropertyCoverage,
					propertyLearningRate, refMapping);
		} else {
			cloned = new DecisionTree(dtl, sourceCache, targetCache, parentMapping, pseudoFMeasure, minPropertyCoverage,
					propertyLearningRate, null, isLeftNode, refMapping);
		}
		cloned.classifier = new ExtendedClassifier(classifier.getMeasure(), classifier.getThreshold(),
				classifier.getSourceProperty(), classifier.getTargetProperty());
		cloned.checked = checked;
		cloned.depth = depth;
		if (leftChild != null) {
			DecisionTree leftClone = leftChild.cloneWithoutParent();
			leftClone.parent = cloned;
			cloned.leftChild = leftClone;
		}
		if (rightChild != null) {
			DecisionTree rightClone = rightChild.cloneWithoutParent();
			rightClone.parent = cloned;
			cloned.rightChild = rightClone;
		}
		return cloned;
	}

	protected DecisionTree cloneWithoutChild(boolean withoutLeft) {
		DecisionTree cloned = null;
		DecisionTree parentClone = null;
		if (this.parent != null) {
			parentClone = this.parent.cloneWithoutChild(isLeftNode);
		}
		if (root) {
			cloned = new DecisionTree(dtl, sourceCache, targetCache, pseudoFMeasure, minPropertyCoverage,
					propertyLearningRate, refMapping);
		} else {
			cloned = new DecisionTree(dtl, sourceCache, targetCache, parentMapping, pseudoFMeasure, minPropertyCoverage,
					propertyLearningRate, parentClone, isLeftNode, refMapping);
		}
		cloned.classifier = new ExtendedClassifier(classifier.getMeasure(), classifier.getThreshold());
		cloned.checked = checked;
		cloned.depth = depth;
		if (parentClone != null) {
			if (this.isLeftNode) {
				parentClone.leftChild = cloned;
				parentClone.rightChild = this.parent.rightChild.cloneWithoutParent();
			} else {
				parentClone.rightChild = cloned;
				parentClone.leftChild = this.parent.leftChild.cloneWithoutParent();
			}
		}
		return cloned;
	}

	/**
	 * @return initial classifiers
	 */
	private List<ExtendedClassifier> findClassifiers() {
		// logger.info("Getting all classifiers ...");
		List<ExtendedClassifier> initialClassifiers = new ArrayList<>();
		for (PairSimilar<String> propPair : dtl.getPropertyMapping().stringPropPairs) {
			for (String measure : DecisionTreeLearning.defaultMeasures) {
				ExtendedClassifier cp = findClassifier(propPair.a, propPair.b, measure);
				if (cp != null)
					initialClassifiers.add(cp);
			}
		}

		// logger.info("Done computing all classifiers.");
		return initialClassifiers;
	}

	private ExtendedClassifier findClassifier(String sourceProperty, String targetProperty, String measure) {
		String measureExpression = measure + "(x." + sourceProperty + ",y." + targetProperty + ")";
		String properties = "(x." + sourceProperty + ",y." + targetProperty + ")";
		ExtendedClassifier cp = new ExtendedClassifier(measure, 0.0, sourceProperty, targetProperty);
		if (this.parent != null) {
			if (this.parent.getPathString().contains(properties)) {
				return null;
			}
		}
		double maxFM = 0.0;
		double theta = 1.0;
		AMapping bestMapping = MappingFactory.createDefaultMapping();
		// PseudoRefFMeasure prfm = new PseudoRefFMeasure();
		// GoldStandard gs = new GoldStandard(null, sourceCache.getAllUris(),
		// targetCache.getAllUris());
		for (double threshold = 1d; threshold > minPropertyCoverage; threshold = threshold * propertyLearningRate) {
			cp = new ExtendedClassifier(measure, threshold, sourceProperty, targetProperty);
			AMapping mapping = getMeasureMapping(measureExpression, cp);
			// double pfm = prfm.calculate(mapping, gs, 0.1);
			double pfm = calculateFMeasure(mapping, refMapping);
			// System.out.println(measureExpression + "|" +threshold+ " " +
			// pfm);
			if (maxFM < pfm) { // only interested in largest threshold with
								// highest F-Measure
				bestMapping = mapping;
				theta = threshold;
				maxFM = pfm;
			}
		}
		cp = new ExtendedClassifier(measure, theta, sourceProperty, targetProperty);
		cp.setfMeasure(maxFM);
		cp.setMapping(executeAtomicMeasure(measureExpression, theta));
		return cp;
	}

	private double calculateFMeasure(AMapping mapping, AMapping refMap) {
		// if(mapping.toString().contains("<") &&
		// !refMap.toString().contains("<") || !mapping.toString().contains("<")
		// && refMap.toString().contains("<")){
		// System.err.println("\n\n\n ====!=!=!=!=!= BRACKET PROBLEM
		// ===!=!=!==!=!= \n\n\n");
		// }
		double res = 0.0;
		if (isSupervised) {
			GoldStandard gs = new GoldStandard(refMap, testSourceCache.getAllUris(), testTargetCache.getAllUris());
			FMeasure fm = new FMeasure();
			res = fm.calculate(mapping, gs);
		} else {
			GoldStandard gs = new GoldStandard(null, sourceCache.getAllUris(), targetCache.getAllUris());
			PseudoRefFMeasure prfm = new PseudoRefFMeasure();
			res = prfm.calculate(mapping, gs);
		}
		return res;
	}

	private AMapping getMeasureMapping(String measureExpression, ExtendedClassifier cp) {
		 if (this.root) {
             AMapping mapping = executeAtomicMeasure(measureExpression,
             cp.getThreshold());
             calculatedMappings.put(cp.getMetricExpression(), mapping);
             return mapping;
		 }
		// AMapping currentClassifierMapping =
		// calculatedMappings.get(cp.getMetricExpression());
		// if (this.isLeftNode) {
		// AMapping differenceMapping =
		// MappingOperations.difference(currentClassifierMapping,
		// parentMapping);
		// return differenceMapping;
		// }
		// AMapping joinedMapping =
		// MappingOperations.intersection(currentClassifierMapping,
		// parentMapping);
		// return joinedMapping;
		classifier = cp;
		classifier.setMapping(calculatedMappings.get(cp.getMetricExpression()));
//		return dtl.predict(testSourceCache, testTargetCache,
//				new MLResults(getTotalLS(), null, -1.0, null));
		return getTotalMapping();
	}

	public AMapping executeAtomicMeasure(String measureExpression, double threshold) {
		Instruction inst = new Instruction(Instruction.Command.RUN, measureExpression, threshold + "", -1, -1, -1);
		ExecutionEngine ee = ExecutionEngineFactory.getEngine(ExecutionEngineType.DEFAULT, testSourceCache,
				testTargetCache, "?x", "?y");
		Plan plan = new Plan();
		plan.addInstruction(inst);
		return ((SimpleExecutionEngine) ee).executeInstructions(plan);
	}

	@Override
	public String toString() {
		String res = "";
		if (classifier != null) {
			res += classifier.getMeasure() + TreeParser.delimiter + classifier.getSourceProperty() + "|"
					+ classifier.getTargetProperty() + ": <= " + classifier.getThreshold() + ", > "
					+ classifier.getThreshold() + "[";
			if (leftChild != null) {
				res += leftChild.toString();
			} else {
				res += "negative (0)";
			}
			res += "][";
			if (rightChild != null) {
				res += rightChild.toString();
			} else {
				res += "positive (0)";
			}
			res += "]";
		} else {
			res += "Classifier not set yet";
		}
		return res;
	}

	public String toStringPretty() {
		String res = "\n";
		res += new String(new char[depth]).replace("\0", "\t");
		if (classifier != null) {
			res += classifier.getMeasure() + TreeParser.delimiter + classifier.getSourceProperty() + "|"
					+ classifier.getTargetProperty() + ": <= " + classifier.getThreshold() + ", > "
					+ classifier.getThreshold() + "[";
			if (leftChild != null) {
				res += leftChild.toStringPretty();
			} else {
				res += "negative (0)";
			}
			res += "][";
			if (rightChild != null) {
				res += rightChild.toStringPretty();
			} else {
				res += "positive (0)";
			}
			res += "]";
		} else {
			res += "Classifier not set yet";
		}
		return res;
	}

	public static void main(String[] args) {
		String resultStr = "";

		final int EUCLID_ITERATIONS = 20;
		final double MIN_COVERAGE = 0.6d;
		String[] datasets = { /* "dbplinkedmdb",*/ "person1", "person2",  "drugs", "restaurantsfixed" };
		// String data = "person2";
		for (String data : datasets) {
			EvaluationData c = DataSetChooser.getData(data);

			System.out.println("\n\n >>>>>>>>>>>>> " + data.toUpperCase() + "<<<<<<<<<<<<<<<<<\n\n");
			// Training phase
			// System.out.println("\n========EUCLID==========");
			//
			// LinearMeshSelfConfigurator lsc = null;
			// Cache sourceCache = new HybridCache();
			// Cache targetCache = new HybridCache();
			// for(Instance i : c.getSourceCache().getAllInstances()){
			// de.uni_leipzig.simba.data.Instance newI = new
			// de.uni_leipzig.simba.data.Instance(i.getUri());
			// for (String property : i.getAllProperties()) {
			// newI.addProperty(property, i.getProperty(property));
			// }
			// sourceCache.addInstance(newI);
			// }
			// for(Instance i : c.getTargetCache().getAllInstances()){
			// de.uni_leipzig.simba.data.Instance newI = new
			// de.uni_leipzig.simba.data.Instance(i.getUri());
			// for (String property : i.getAllProperties()) {
			// newI.addProperty(property, i.getProperty(property));
			// }
			// targetCache.addInstance(newI);
			// }
			// lsc = new LinearMeshSelfConfigurator(sourceCache, targetCache,
			// MIN_COVERAGE, 1d);
			// // logger.info("Running " + euclideType);
			// lsc.setMeasure(new PseudoMeasures());
			// long begin = System.currentTimeMillis();
			// // logger.info("Computing simple classifiers...");
			// List<SimpleClassifier> cp = lsc.getBestInitialClassifiers();
			// ComplexClassifier cc = lsc.getZoomedHillTop(5, EUCLID_ITERATIONS,
			// cp);
			// long durationMS = System.currentTimeMillis() - begin;
			// Mapping learnedMap = cc.mapping;
			// learnedMap.initReversedMap();
			// String metricExpr = cc.toString();
			// System.out.println("Learned: " + metricExpr);
			//
			// resultStr +=
			// // PRFCalculator.precision(learnedMap, trainData.map) + "\t" +
			// // PRFCalculator.recall(learnedMap, trainData.map) + "\t" +
			// // PRFCalculator.fScore(learnedMap, trainData.map) + "\t" +
			// durationMS + "\t";
			// // +
			// // metricExpr + "\t" ;
			//
			// // Test phase
			// lsc.setSource(sourceCache);
			// lsc.setTarget(targetCache);
			// begin = System.currentTimeMillis();
			// Mapping oldlearnedTestMap = lsc.getMapping(cc.classifiers);
			// AMapping learnedTestMap = MappingFactory.createDefaultMapping();
			// for(String s: oldlearnedTestMap.map.keySet()){
			// learnedTestMap.add(s, oldlearnedTestMap.map.get(s));
			// }
			// GoldStandard gs = new GoldStandard(c.getReferenceMapping(),
			// c.getSourceCache().getAllUris(),
			// c.getTargetCache().getAllUris());
			// resultStr += new Precision().calculate(learnedTestMap, gs) + "\t"
			// + new Recall().calculate(learnedTestMap, gs) + "\t"
			// + new FMeasure().calculate(learnedTestMap, gs) + "\n";
			// // + "\t" +
			// // durationMS + "\n" ;
			// System.out.println(resultStr);
			// for(int i = 0; i < 10; i++){
			// System.out.println("\n========EAGLE==========");
			// AMLAlgorithm eagle =
			// MLAlgorithmFactory.createMLAlgorithm(Eagle.class,
			// MLImplementationType.UNSUPERVISED);
			// eagle.init(null, c.getSourceCache(), c.getTargetCache());
			// eagle.getMl().setConfiguration(c.getConfigReader().read());
			// eagle.getMl().setParameter(Eagle.PROPERTY_MAPPING,
			// c.getPropertyMapping());
			// long start = System.currentTimeMillis();
			// MLResults resEagle = eagle.asUnsupervised().learn(new
			// PseudoFMeasure());
			// long end = System.currentTimeMillis();
			// System.out.println(resEagle.getLinkSpecification());
			// System.out.println("Learned size: " +
			// eagle.predict(c.getSourceCache(), c.getTargetCache(),
			// resEagle).size());
			// System.out.println("FMeasure: "
			// + new FMeasure().calculate(eagle.predict(c.getSourceCache(),
			// c.getTargetCache(), resEagle),
			// new GoldStandard(c.getReferenceMapping(), c.getSourceCache(),
			// c.getTargetCache())));
			// System.out.println("Time: " + (end - start));
			// }
			// System.out.println("\n========WOMBAT=========="); AMLAlgorithm
			// wombat = MLAlgorithmFactory.createMLAlgorithm(WombatSimple.class,
			// MLImplementationType.UNSUPERVISED); wombat.init(null,
			// c.getSourceCache(), c.getTargetCache());
			// wombat.getMl().setConfiguration(c.getConfigReader().read()); long
			// start = System.currentTimeMillis(); MLResults resWom =
			// wombat.asUnsupervised().learn(new PseudoFMeasure()); long end =
			// System.currentTimeMillis();
			// System.out.println(resWom.getLinkSpecification());
			// System.out.println("FMeasure: " + new
			// FMeasure().calculate(wombat.predict(c.getSourceCache(),
			// c.getTargetCache(), resWom), new
			// GoldStandard(c.getReferenceMapping(),c.getSourceCache(),
			// c.getTargetCache()))); System.out.println("Time: " + (end -
			// start));

			try {
				System.out.println("========DTL==========");
				AMLAlgorithm dtl = MLAlgorithmFactory.createMLAlgorithm(DecisionTreeLearning.class,
						MLImplementationType.SUPERVISED_BATCH);
				dtl.init(null, c.getSourceCache(), c.getTargetCache());
				Configuration config = c.getConfigReader().read();
				dtl.getMl().setConfiguration(config);
				((DecisionTreeLearning) dtl.getMl()).setPropertyMapping(c.getPropertyMapping());
				long start = System.currentTimeMillis();
				dtl.getMl().setParameter(DecisionTreeLearning.PARAMETER_MAX_LINK_SPEC_HEIGHT, 3);
				isSupervised = true;
				DecisionTree.actualRefMapping = c.getReferenceMapping();
				MLResults res = dtl.asSupervised().learn(getTrainingData(c.getReferenceMapping()));
				long end = System.currentTimeMillis();
				System.out.println(res.getLinkSpecification().toStringPretty());
				System.out.println("FMeasure: "
						+ new FMeasure().calculate(dtl.predict(c.getSourceCache(), c.getTargetCache(), res),
								new GoldStandard(c.getReferenceMapping(), c.getSourceCache().getAllUris(),
										c.getTargetCache().getAllUris())));
				System.out.println("Time: " + (end - start));

				// AMapping pathMapping =
				// DecisionTree.getTotalMapping(((DecisionTreeLearning)
				// dtl.getMl()).root);
				// System.out.println("Path FMeasure: " + new
				// FMeasure().calculate(pathMapping,
				// new GoldStandard(c.getReferenceMapping(), c.getSourceCache(),
				// c.getTargetCache())));

			} catch (UnsupportedMLImplementationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public static AMapping getTrainingData(AMapping full) {
		int sliceSizeWanted = full.size() - (int) Math.ceil(((double) full.getSize() / 10.0));
		AMapping slice = MappingFactory.createDefaultMapping();
		Object[] keyArr = full.getMap().keySet().toArray();
		int c = 5;
		int i = 2;
		while (slice.size() <= sliceSizeWanted) {
			// String key = (String)keyArr[(int)(Math.random() *
			// keyArr.length)];
			String key = (String) keyArr[c];
			c = c + i;
			if (c >= keyArr.length) {
				c = 0;
				i++;
			}
			if (!slice.getMap().keySet().contains(key)) {
				slice.add(key, full.getMap().get(key));
			}
		}
		System.out.println("got: " + MappingOperations.intersection(slice, full).size() + " wanted: " + sliceSizeWanted
				+ " full: " + full.size());
		return slice;
	}

	public void setRefMapping(AMapping refMapping) {
		this.refMapping = refMapping;
	}

}
