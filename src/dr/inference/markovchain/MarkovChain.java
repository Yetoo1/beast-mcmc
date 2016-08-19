/*
 * MarkovChain.java
 *
 * Copyright (c) 2002-2015 Alexei Drummond, Andrew Rambaut and Marc Suchard
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package dr.inference.markovchain;

import dr.evomodel.continuous.GibbsIndependentCoalescentOperator;
import dr.inference.model.*;
import dr.inference.operators.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * A concrete markov chain. This is final as the only things that should need
 * overriding are in the delegates (prior, likelihood, schedule and acceptor).
 * The design of this class is to be fairly immutable as far as settings goes.
 *
 * @author Alexei Drummond
 * @author Andrew Rambaut
 * @version $Id: MarkovChain.java,v 1.10 2006/06/21 13:34:42 rambaut Exp $
 */
public final class MarkovChain implements Serializable {
    private static final long serialVersionUID = 181L;


    private final static boolean DEBUG = false;
    private final static boolean PROFILE = true;

    public static final double EVALUATION_TEST_THRESHOLD = 1e-1;

    private final OperatorSchedule schedule;
    private final Acceptor acceptor;
    private final Likelihood jointDensity;

    private boolean pleaseStop = false;
    private boolean isStopped = false;
    private double bestScore, currentScore, initialScore;
    private long currentLength;

    private boolean useCoercion = true;

    private final long fullEvaluationCount;
    private final int minOperatorCountForFullEvaluation;

    private double evaluationTestThreshold = EVALUATION_TEST_THRESHOLD;


    public MarkovChain(Likelihood jointDensity,
                       OperatorSchedule schedule,
                       Acceptor acceptor,
                       long fullEvaluationCount,
                       int minOperatorCountForFullEvaluation,
                       double evaluationTestThreshold,
                       boolean useCoercion) {

        currentLength = 0;
        this.jointDensity = jointDensity;
        this.schedule = schedule;
        this.acceptor = acceptor;
        this.useCoercion = useCoercion;

        this.fullEvaluationCount = fullEvaluationCount;
        this.minOperatorCountForFullEvaluation = minOperatorCountForFullEvaluation;
        this.evaluationTestThreshold = evaluationTestThreshold;

        Likelihood.CONNECTED_SET.add(this.jointDensity);
        Likelihood.CONNECTED_SET.addAll(this.jointDensity.getLikelihoodSet());

        for (Likelihood l : Likelihood.FULL_SET) {
            if (!Likelihood.CONNECTED_SET.contains(l)) {
                System.err.println("WARNING: Likelihood component, " + l.getId() + ", created but not used in the MCMC");
            }
        }

        for (Parameter parameter : Parameter.FULL_SET) {
            if (parameter instanceof Storable) {
                Storable.FULL_SET.add((Storable) parameter);
            }
        }

        for (Model model : Model.FULL_SET) {
            if (!Model.CONNECTED_SET.contains(model)) {
                System.err.println("WARNING: Model component, " + model.getId() + ", created but not used in the MCMC");
            }
            Storable.FULL_SET.add(model);
        }

        currentScore = evaluate(this.jointDensity);
    }

    /**
     * Resets the markov chain
     */
    public void reset() {
        currentLength = 0;

        // reset operator acceptance levels
        for (int i = 0; i < schedule.getOperatorCount(); i++) {
            schedule.getOperator(i).reset();
        }
    }

    /**
     * Run the chain for a given number of states.
     *
     * @param length number of states to run the chain.
     */
    public long runChain(long length, boolean disableCoerce) {

        jointDensity.makeDirty();
        currentScore = evaluate(jointDensity);

        long currentState = currentLength;

        final Model currentModel = jointDensity.getModel();

        if (currentState == 0) {
            initialScore = currentScore;
            bestScore = currentScore;
            fireBestModel(currentState, currentModel);
        }

        if (currentScore == Double.NEGATIVE_INFINITY) {

            // identify which component of the score is zero...
            String message = "The initial likelihood is zero";
            if (jointDensity instanceof CompoundLikelihood) {
                message += ": " + ((CompoundLikelihood) jointDensity).getDiagnosis();
            } else if (jointDensity instanceof PathLikelihood) {
                message += ": " + ((CompoundLikelihood)((PathLikelihood) jointDensity).getSourceLikelihood()).getDiagnosis();
                message += ": " + ((CompoundLikelihood)((PathLikelihood) jointDensity).getDestinationLikelihood()).getDiagnosis();
            } else {
                message += ".";
            }
            throw new IllegalArgumentException(message);
        } else if (currentScore == Double.POSITIVE_INFINITY || Double.isNaN(currentScore)) {
            String message = "A likelihood returned with a numerical error";
            if (jointDensity instanceof CompoundLikelihood) {
                message += ": " + ((CompoundLikelihood) jointDensity).getDiagnosis();
            } else {
                message += ".";
            }
            throw new IllegalArgumentException(message);
        }

        pleaseStop = false;
        isStopped = false;

        //int otfcounter = onTheFlyOperatorWeights > 0 ? onTheFlyOperatorWeights : 0;

        double[] logr = {0.0};

        boolean usingFullEvaluation = true;
        // set ops count in mcmc element instead
        if (fullEvaluationCount == 0) // Temporary solution until full code review
            usingFullEvaluation = false;
        boolean fullEvaluationError = false;

        while (!pleaseStop && (currentState < (currentLength + length))) {

            String diagnosticStart = "";

            // periodically log states
            fireCurrentModel(currentState, currentModel);

            if (pleaseStop) {
                isStopped = true;
                break;
            }

            // Get the operator
            final int op = schedule.getNextOperatorIndex();
            final MCMCOperator mcmcOperator = schedule.getOperator(op);

            double oldScore = currentScore;
            if (usingFullEvaluation) {
                diagnosticStart = jointDensity instanceof CompoundLikelihood ?
                        ((CompoundLikelihood) jointDensity).getDiagnosis() : "";
            }

            // assert Profiler.startProfile("Store");

            // The current model is stored here in case the proposal fails
//            if (currentModel != null) {
//                currentModel.storeModelState();
//            }
            for (Storable storable : Storable.FULL_SET) {
                storable.storeModelState();
            }

            // assert Profiler.stopProfile("Store");

            boolean operatorSucceeded = true;
            double hastingsRatio = 1.0;
            boolean accept = false;

            logr[0] = -Double.MAX_VALUE;

            try {
                // The new model is proposed
                // assert Profiler.startProfile("Operate");

                if (DEBUG) {
                    System.out.println("\n&& Operator: " + mcmcOperator.getOperatorName());
                }

                if (mcmcOperator instanceof GeneralOperator) {
                    hastingsRatio = ((GeneralOperator) mcmcOperator).operate(jointDensity);
                } else {
                    hastingsRatio = mcmcOperator.operate();
                }

                // assert Profiler.stopProfile("Operate");
            } catch (OperatorFailedException e) {
                operatorSucceeded = false;
            }

            double score = Double.NaN;
            double deviation = Double.NaN;

            //    System.err.print("" + currentState + ": ");
            if (operatorSucceeded) {

                // The new model is proposed
                // assert Profiler.startProfile("Evaluate");

                if (DEBUG) {
                    System.out.println("** Evaluate");
                }

                long elapsedTime = 0;
                if (PROFILE) {
                    elapsedTime = System.currentTimeMillis();
                }

                // The new model is evaluated
                score = evaluate(jointDensity);

                if (PROFILE) {
                    long duration = System.currentTimeMillis() - elapsedTime;
                    if (DEBUG) {
                        System.out.println("Time: " + duration);
                    }
                    mcmcOperator.addEvaluationTime(duration);
                }

                String diagnosticOperator = "";
                if (usingFullEvaluation) {
                    diagnosticOperator = jointDensity instanceof CompoundLikelihood ?
                            ((CompoundLikelihood) jointDensity).getDiagnosis() : "";
                }

                if (score == Double.NEGATIVE_INFINITY && mcmcOperator instanceof GibbsOperator) {
                    if (!(mcmcOperator instanceof GibbsIndependentNormalDistributionOperator) && !(mcmcOperator instanceof GibbsIndependentGammaOperator) && !(mcmcOperator instanceof GibbsIndependentCoalescentOperator) && !(mcmcOperator instanceof GibbsIndependentJointNormalGammaOperator)) {
                        Logger.getLogger("error").severe("State " + currentState + ": A Gibbs operator, " + mcmcOperator.getOperatorName() + ", returned a state with zero likelihood.");
                    }
                }

                if (score == Double.POSITIVE_INFINITY ||
                        Double.isNaN(score) ) {
                    if (jointDensity instanceof CompoundLikelihood) {
                        Logger.getLogger("error").severe("State "+currentState+": A likelihood returned with a numerical error:\n" +
                                ((CompoundLikelihood) jointDensity).getDiagnosis());
                    } else {
                        Logger.getLogger("error").severe("State "+currentState+": A likelihood returned with a numerical error.");
                    }

                    // If the user has chosen to ignore this error then we transform it
                    // to a negative infinity so the state is rejected.
                    score = Double.NEGATIVE_INFINITY;
                }

                if (usingFullEvaluation) {

                    // This is a test that the state was correctly evaluated. The
                    // likelihood of all components of the model are flagged as
                    // needing recalculation, then the full likelihood is calculated
                    // again and compared to the first result. This checks that the
                    // BEAST is aware of all changes that the operator induced.

                    jointDensity.makeDirty();
                    final double testScore = evaluate(jointDensity);

                    final String d2 = jointDensity instanceof CompoundLikelihood ?
                            ((CompoundLikelihood) jointDensity).getDiagnosis() : "";

                    if (Math.abs(testScore - score) > evaluationTestThreshold) {
                        Logger.getLogger("error").severe(
                                "State "+currentState+": State was not correctly calculated after an operator move.\n"
                                        + "Likelihood evaluation: " + score
                                        + "\nFull Likelihood evaluation: " + testScore
                                        + "\n" + "Operator: " + mcmcOperator
                                        + " " + mcmcOperator.getOperatorName()
                                        + (diagnosticOperator.length() > 0 ? "\n\nDetails\nBefore: " + diagnosticOperator + "\nAfter: " + d2 : "")
                                        + "\n\n");
                        fullEvaluationError = true;
                    }
                }

                if (score > bestScore) {
                    bestScore = score;
                    fireBestModel(currentState, currentModel);
                }

                accept = mcmcOperator instanceof GibbsOperator || acceptor.accept(oldScore, score, hastingsRatio, logr);

                deviation = score - oldScore;
            }

            // The new model is accepted or rejected
            if (accept) {
                if (DEBUG) {
                    System.out.println("** Move accepted: new score = " + score
                            + ", old score = " + oldScore);
                }

                mcmcOperator.accept(deviation);

                for (Storable storable : Storable.FULL_SET) {
                    storable.acceptModelState();
                }

                currentScore = score;

            } else {
                if (DEBUG) {
                    System.out.println("** Move rejected: new score = " + score
                            + ", old score = " + oldScore);
                }

                mcmcOperator.reject();

                // assert Profiler.startProfile("Restore");

//                currentModel.restoreModelState();
                for (Storable storable : Storable.FULL_SET) {
                    storable.restoreModelState();
                }

                if (usingFullEvaluation) {
                    // This is a test that the state is correctly restored. The
                    // restored state is fully evaluated and the likelihood compared with
                    // that before the operation was made.

                    jointDensity.makeDirty();
                    final double testScore = evaluate(jointDensity);

                    final String d2 = jointDensity instanceof CompoundLikelihood ?
                            ((CompoundLikelihood) jointDensity).getDiagnosis() : "";

                    if (Math.abs(testScore - oldScore) > evaluationTestThreshold) {


                        final Logger logger = Logger.getLogger("error");
                        logger.severe("State "+currentState+": State was not correctly restored after reject step.\n"
                                + "Likelihood before: " + oldScore
                                + " Likelihood after: " + testScore
                                + "\n" + "Operator: " + mcmcOperator
                                + " " + mcmcOperator.getOperatorName()
                                + (diagnosticStart.length() > 0 ? "\n\nDetails\nBefore: " + diagnosticStart + "\nAfter: " + d2 : "")
                                + "\n\n");
                        fullEvaluationError = true;
                    }
                }
            }
            // assert Profiler.stopProfile("Restore");


            if (!disableCoerce && mcmcOperator instanceof CoercableMCMCOperator) {
                coerceAcceptanceProbability((CoercableMCMCOperator) mcmcOperator, logr[0]);
            }

            if (usingFullEvaluation) {
                if (schedule.getMinimumAcceptAndRejectCount() >= minOperatorCountForFullEvaluation &&
                        currentState >= fullEvaluationCount) {
                    // full evaluation is only switched off when each operator has done a
                    // minimum number of operations (currently 1) and fullEvalationCount
                    // operations in total.

                    usingFullEvaluation = false;
                    if (fullEvaluationError) {
                        // If there has been an error then stop with an error
                        throw new RuntimeException(
                                "One or more evaluation errors occurred during the test phase of this\n" +
                                        "run. These errors imply critical errors which may produce incorrect\n" +
                                        "results.");
                    }
                }
            }

            fireEndCurrentIteration(currentState);

            currentState += 1;
        }

        currentLength = currentState;

        return currentLength;
    }

    public void terminateChain() {
        fireFinished(currentLength);

        // Profiler.report();
    }

    public Model getModel() {
        return jointDensity.getModel();
    }

    public OperatorSchedule getSchedule() {
        return schedule;
    }

    public Acceptor getAcceptor() {
        return acceptor;
    }

    public double getInitialScore() {
        return initialScore;
    }

    public double getBestScore() {
        return bestScore;
    }

    public long getCurrentLength() {
        return currentLength;
    }

    public void setCurrentLength(long currentLength) {
        this.currentLength = currentLength;
    }

    public double getCurrentScore() {
        return currentScore;
    }

    public void pleaseStop() {
        pleaseStop = true;
    }

    public boolean isStopped() {
        return isStopped;
    }

    public double evaluate() {
        return evaluate(jointDensity);
    }

    protected double evaluate(Likelihood jointDensity) {

        final double logP = jointDensity.getLogLikelihood();

        if (Double.isNaN(logP)) {
            return Double.NEGATIVE_INFINITY;
        }

        return logP;
    }

    /**
     * Updates the proposal parameter, based on the target acceptance
     * probability This method relies on the proposal parameter being a
     * decreasing function of acceptance probability.
     *
     * @param op   The operator
     * @param logr
     */
    private void coerceAcceptanceProbability(CoercableMCMCOperator op, double logr) {

        if (isCoercable(op)) {
            final double p = op.getCoercableParameter();

            final double i = schedule.getOptimizationTransform(MCMCOperator.Utils.getOperationCount(op));

            final double target = op.getTargetAcceptanceProbability();

            final double newp = p + ((1.0 / (i + 1.0)) * (Math.exp(logr) - target));

            if (newp > -Double.MAX_VALUE && newp < Double.MAX_VALUE) {
                op.setCoercableParameter(newp);
            }
        }
    }

    private boolean isCoercable(CoercableMCMCOperator op) {

        return op.getMode() == CoercionMode.COERCION_ON
                || (op.getMode() != CoercionMode.COERCION_OFF && useCoercion);
    }

    public void addMarkovChainListener(MarkovChainListener listener) {
        listeners.add(listener);
    }

    public void removeMarkovChainListener(MarkovChainListener listener) {
        listeners.remove(listener);
    }

    public void addMarkovChainDelegate(MarkovChainDelegate delegate) {
        delegates.add(delegate);
    }

    public void removeMarkovChainDelegate(MarkovChainDelegate delegate) {
        delegates.remove(delegate);
    }


    private void fireBestModel(long state, Model bestModel) {

        for (MarkovChainListener listener : listeners) {
            listener.bestState(state, bestModel);
        }
    }

    private void fireCurrentModel(long state, Model currentModel) {
        for (MarkovChainListener listener : listeners) {
            listener.currentState(state, currentModel);
        }

        for (MarkovChainDelegate delegate : delegates) {
            delegate.currentState(state);
        }
    }

    private void fireFinished(long chainLength) {

        for (MarkovChainListener listener : listeners) {
            listener.finished(chainLength);
        }

        for (MarkovChainDelegate delegate : delegates) {
            delegate.finished(chainLength);
        }
    }

    private void fireEndCurrentIteration(long state) {
        for (MarkovChainDelegate delegate : delegates) {
            delegate.currentStateEnd(state);
        }
    }

    private final ArrayList<MarkovChainListener> listeners = new ArrayList<MarkovChainListener>();
    private final ArrayList<MarkovChainDelegate> delegates = new ArrayList<MarkovChainDelegate>();
}
