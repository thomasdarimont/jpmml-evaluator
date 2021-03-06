/*
 * Copyright (c) 2013 Villu Ruusmann
 *
 * This file is part of JPMML-Evaluator
 *
 * JPMML-Evaluator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Evaluator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Evaluator.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.evaluator;

import java.util.*;

import org.jpmml.manager.*;

import org.dmg.pmml.*;

import com.google.common.cache.*;
import com.google.common.collect.*;

public class TreeModelEvaluator extends ModelEvaluator<TreeModel> implements HasEntityRegistry<Node> {

	public TreeModelEvaluator(PMML pmml){
		this(pmml, find(pmml.getModels(), TreeModel.class));
	}

	public TreeModelEvaluator(PMML pmml, TreeModel treeModel){
		super(pmml, treeModel);
	}

	@Override
	public String getSummary(){
		return "Tree model";
	}

	@Override
	public BiMap<String, Node> getEntityRegistry(){
		return getValue(TreeModelEvaluator.entityCache);
	}

	@Override
	public Map<FieldName, ?> evaluate(ModelEvaluationContext context){
		TreeModel treeModel = getModel();
		if(!treeModel.isScorable()){
			throw new InvalidResultException(treeModel);
		}

		Node node;

		MiningFunctionType miningFunction = treeModel.getFunctionName();
		switch(miningFunction){
			case REGRESSION:
			case CLASSIFICATION:
				node = evaluateTree(context);
				break;
			default:
				throw new UnsupportedFeatureException(treeModel, miningFunction);
		}

		NodeClassificationMap values = null;

		if(node != null){
			values = createNodeClassificationMap(node);
		}

		Map<FieldName, ? extends ClassificationMap<?>> predictions = TargetUtil.evaluateClassification(values, context);

		return OutputUtil.evaluate(predictions, context);
	}

	private Node evaluateTree(ModelEvaluationContext context){
		TreeModel treeModel = getModel();

		Node root = treeModel.getNode();
		if(root == null){
			throw new InvalidFeatureException(treeModel);
		}

		LinkedList<Node> trail = Lists.newLinkedList();

		NodeResult result = new NodeResult(null);

		Boolean status = evaluateNode(root, context);
		if(status == null){
			result = handleMissingValue(root, trail, context);
		} else

		if(status.booleanValue()){
			result = handleTrue(root, trail, context);
		} // End if

		if(result == null){
			throw new MissingResultException(root);
		}

		Node node = result.getNode();

		if(node != null || result.isFinal()){
			return node;
		}

		NoTrueChildStrategyType noTrueChildStrategy = treeModel.getNoTrueChildStrategy();
		switch(noTrueChildStrategy){
			case RETURN_NULL_PREDICTION:
				return null;
			case RETURN_LAST_PREDICTION:
				return lastPrediction(root, trail);
			default:
				throw new UnsupportedFeatureException(treeModel, noTrueChildStrategy);
		}
	}

	private NodeResult handleMissingValue(Node node, LinkedList<Node> trail, EvaluationContext context){
		TreeModel treeModel = getModel();

		MissingValueStrategyType missingValueStrategy = treeModel.getMissingValueStrategy();
		switch(missingValueStrategy){
			case NULL_PREDICTION:
				return new FinalNodeResult(null);
			case LAST_PREDICTION:
				return new FinalNodeResult(lastPrediction(node, trail));
			case NONE:
				return null;
			default:
				throw new UnsupportedFeatureException(treeModel, missingValueStrategy);
		}
	}

	private NodeResult handleTrue(Node node, LinkedList<Node> trail, EvaluationContext context){
		List<Node> children = node.getNodes();

		// A "true" leaf node
		if(children.isEmpty()){
			return new NodeResult(node);
		}

		trail.add(node);

		for(Node child : children){
			Boolean status = evaluateNode(child, context);

			if(status == null){
				NodeResult result = handleMissingValue(child, trail, context);
				if(result != null){
					return result;
				}
			} else

			if(status.booleanValue()){
				return handleTrue(child, trail, context);
			}
		}

		// A branch node with no "true" leaf nodes
		return new NodeResult(null);
	}

	private Node lastPrediction(Node node, LinkedList<Node> trail){

		try {
			return trail.getLast();
		} catch(NoSuchElementException nsee){
			throw new MissingResultException(node);
		}
	}

	private Boolean evaluateNode(Node node, EvaluationContext context){
		Predicate predicate = node.getPredicate();
		if(predicate == null){
			throw new InvalidFeatureException(node);
		}

		EmbeddedModel embeddedModel = node.getEmbeddedModel();
		if(embeddedModel != null){
			throw new UnsupportedFeatureException(embeddedModel);
		}

		return PredicateUtil.evaluate(predicate, context);
	}

	static
	private NodeClassificationMap createNodeClassificationMap(Node node){
		NodeClassificationMap result = new NodeClassificationMap(node);

		List<ScoreDistribution> scoreDistributions = node.getScoreDistributions();

		double sum = 0;

		for(ScoreDistribution scoreDistribution : scoreDistributions){
			sum += scoreDistribution.getRecordCount();
		} // End for

		for(ScoreDistribution scoreDistribution : scoreDistributions){
			Double value = scoreDistribution.getProbability();
			if(value == null){
				value = (scoreDistribution.getRecordCount() / sum);
			}

			result.put(scoreDistribution.getValue(), value);
		}

		return result;
	}

	static
	private class NodeResult {

		private Node node = null;


		public NodeResult(Node node){
			setNode(node);
		}

		/**
		 * @return <code>true</code> if the result should be exempt from any post-processing (eg. "no true child strategy" treatment), <code>false</code> otherwise.
		 */
		public boolean isFinal(){
			return false;
		}

		public Node getNode(){
			return this.node;
		}

		private void setNode(Node node){
			this.node = node;
		}
	}

	static
	private class FinalNodeResult extends NodeResult {

		public FinalNodeResult(Node node){
			super(node);
		}

		@Override
		public boolean isFinal(){
			return true;
		}
	}

	private static final LoadingCache<TreeModel, BiMap<String, Node>> entityCache = CacheBuilder.newBuilder()
		.weakKeys()
		.build(new CacheLoader<TreeModel, BiMap<String, Node>>(){

			@Override
			public BiMap<String, Node> load(TreeModel treeModel){
				BiMap<String, Node> result = HashBiMap.create();

				collectNodes(treeModel.getNode(), result);

				return result;
			}

			private void collectNodes(Node node, BiMap<String, Node> result){
				EntityUtil.put(node, result);

				List<Node> children = node.getNodes();
				for(Node child : children){
					collectNodes(child, result);
				}
			}
		});
}