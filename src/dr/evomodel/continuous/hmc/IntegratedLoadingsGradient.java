package dr.evomodel.continuous.hmc;

import dr.evolution.tree.Tree;
import dr.evolution.tree.TreeTrait;
import dr.evomodel.treedatalikelihood.DataLikelihoodDelegate;
import dr.evomodel.treedatalikelihood.TreeDataLikelihood;
import dr.evomodel.treedatalikelihood.continuous.ContinuousDataLikelihoodDelegate;
import dr.evomodel.treedatalikelihood.continuous.IntegratedFactorAnalysisLikelihood;
import dr.evomodel.treedatalikelihood.preorder.WrappedMeanPrecision;
import dr.evomodel.treedatalikelihood.preorder.WrappedTipFullConditionalDistributionDelegate;
import dr.inference.hmc.GradientWrtParameterProvider;
import dr.inference.model.Likelihood;
import dr.inference.model.Parameter;
import dr.math.matrixAlgebra.WrappedVector;
import dr.xml.*;

import java.util.List;

import static dr.evomodel.continuous.hmc.LinearOrderTreePrecisionTraitProductProvider.castTreeTrait;

/**
 * @author Marc A. Suchard
 * @author Andrew Holbrook
 */
public class IntegratedLoadingsGradient implements GradientWrtParameterProvider, Reportable {

    private final TreeTrait<List<WrappedMeanPrecision>> fullConditionalDensity;
    private final IntegratedFactorAnalysisLikelihood factorAnalysisLikelihood;
    private final int dim;
    private final Tree tree;

    private IntegratedLoadingsGradient(TreeDataLikelihood treeDataLikelihood,
                               ContinuousDataLikelihoodDelegate likelihoodDelegate,
                               IntegratedFactorAnalysisLikelihood factorAnalysisLikelihood) {

        this.factorAnalysisLikelihood = factorAnalysisLikelihood;

        String traitName = factorAnalysisLikelihood.getModelName(); // TODO Is this correct?

        String fcdName = WrappedTipFullConditionalDistributionDelegate.getName(traitName);
        if (treeDataLikelihood.getTreeTrait(fcdName) == null) {
            likelihoodDelegate.addWrappedFullConditionalDensityTrait(traitName);
        }

        this.fullConditionalDensity = castTreeTrait(treeDataLikelihood.getTreeTrait(fcdName));
        this.tree = treeDataLikelihood.getTree();
        this.dim = factorAnalysisLikelihood.getTraitDimension();
    }

    @Override
    public Likelihood getLikelihood() {
        return null;    // TODO Return CompoundLikelihood{factorAnalysisLikelihood, treeDataLikelihood}
    }

    @Override
    public Parameter getParameter() {
        return factorAnalysisLikelihood.getLoadings(); // TODO May need to work with vec(L)
    }

    @Override
    public int getDimension() {
        return dim * dim; // TODO May need to work with vec(L)
    }

    @Override
    public double[] getGradientLogDensity() {

        double[] gradient = new double[getDimension()];

        for (int i = 0; i < tree.getExternalNodeCount(); ++i) {
            // TODO Work with fullConditionalDensity
            List<WrappedMeanPrecision> statistics =
                    fullConditionalDensity.getTrait(tree, tree.getExternalNode(i));

            for (WrappedMeanPrecision meanPrecision : statistics) {
                meanPrecision.getMean();
                meanPrecision.getPrecision();
            }
        }

        if (DEBUG) {
            System.err.println(getReport(gradient));
        }

        return gradient;
    }
    
    @Override
    public String getReport() {
        return getReport(getGradientLogDensity());
    }

    private String getReport(double[] gradient) {

        String result = new WrappedVector.Raw(gradient).toString();

         if (DEBUG) {
             result += "Debug info: \n";
         }

         return result;
    }

    private static final boolean DEBUG = true;

    private static final String PARSER_NAME = "integratedFactorAnalysisLoadingsGradient";

    public static AbstractXMLObjectParser PARSER = new AbstractXMLObjectParser() {
        @Override
        public Object parseXMLObject(XMLObject xo) throws XMLParseException {

            TreeDataLikelihood treeDataLikelihood = (TreeDataLikelihood)
                    xo.getChild(TreeDataLikelihood.class);

            IntegratedFactorAnalysisLikelihood factorAnalysis = (IntegratedFactorAnalysisLikelihood)
                    xo.getChild(IntegratedFactorAnalysisLikelihood.class);

            DataLikelihoodDelegate likelihoodDelegate = treeDataLikelihood.getDataLikelihoodDelegate();

            if (!(likelihoodDelegate instanceof ContinuousDataLikelihoodDelegate)) {
                throw new XMLParseException("TODO");
            }

            ContinuousDataLikelihoodDelegate continuousDataLikelihoodDelegate =
                    (ContinuousDataLikelihoodDelegate) likelihoodDelegate;

            // TODO Check dimensions, parameters, etc.

            return new IntegratedLoadingsGradient(
                    treeDataLikelihood,
                    continuousDataLikelihoodDelegate,
                    factorAnalysis);
        }

        @Override
        public XMLSyntaxRule[] getSyntaxRules() {
            return rules;
        }

        @Override
        public String getParserDescription() {
            return "Generates a gradient provider for the loadings matrix when factors are integrated out";
        }

        @Override
        public Class getReturnType() {
            return IntegratedLoadingsGradient.class;
        }

        @Override
        public String getParserName() {
            return PARSER_NAME;
        }

        private final XMLSyntaxRule[] rules = new XMLSyntaxRule[] {
                new ElementRule(IntegratedFactorAnalysisLikelihood.class),
                new ElementRule(TreeDataLikelihood.class),
        };
    };
}
