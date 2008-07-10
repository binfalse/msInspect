/*
 * Copyright (c) 2003-2007 Fred Hutchinson Cancer Research Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fhcrc.cpl.viewer.amt;

import java.util.*;
import java.util.List;
import java.io.IOException;
import java.io.File;
import java.awt.*;

import org.apache.log4j.Logger;
import org.fhcrc.cpl.viewer.feature.Feature;
import org.fhcrc.cpl.viewer.feature.extraInfo.AmtExtraInfoDef;
import org.fhcrc.cpl.viewer.feature.extraInfo.MS2ExtraInfoDef;
import org.fhcrc.cpl.viewer.gui.util.*;
import org.fhcrc.cpl.toolbox.*;
import org.fhcrc.cpl.toolbox.proteomics.ModifiedAminoAcid;

/**
 * This class assigns a probability to every AMT match, based on the distribution of
 * target and decoy mass and H errors.
 *
 * First, all matches (including multiple matches to the same feature) are mined for their mass
 * and H error values.  Those values are fed to an Expectation-Maximization algorithm, implemented
 * in R, which estimates parameters for a mixed distribution.  We assume the true distribution to
 * be a uniform distribution of false matches mixed with a bivariate normal distribution of true
 * matches. We also feed the EM algorithm an initial estimate of the proportion of matches in the
 * bivariate normal distribution, which is based on a match to a decoy database.
 *
 * The EM algorithm returns distribution parameters, as well as a probability for each match indicating
 * how likely it is that the match is in the bivariate normal distribution of true matches.
 *
 * Lots of charts are provided in order to let the user judge the success of the algorithm in modeling
 * the distribution.   
 */
public class AmtMatchProbabilityAssigner
{
    static Logger _log = Logger.getLogger(AmtMatchProbabilityAssigner.class);

    //values for scaffolding printlns
    protected static float minXScaff = 450f;
    protected static float maxXScaff = 500f;
    protected static float minYScaff = -600f;
    protected static float maxYScaff = -500f;
    protected boolean isInScaffRange(double x, double y)
    { return (x>=minXScaff && x<=maxXScaff && y>=minYScaff && y<=maxYScaff); }

    //maximum time to wait for R to assign probabilities.  Any value here is dangerous.
    public static final int MAX_R_PROB_ASSIGNMENT_MILLIS = 900000;
    protected int maxRProbAssignmentMillis = MAX_R_PROB_ASSIGNMENT_MILLIS;

    //boundaries for the entire match space
    protected float minDeltaMass;
    protected float maxDeltaMass;
    protected float minDeltaElution;
    protected float maxDeltaElution;


    //ranges for the mass space, calculated from the above
    protected float totalMassRange;
    protected float totalElutionRange;

    //Use EM estimation for probabilities, rather than nonparametric
    public static final float DEFAULT_MIN_EM_ITERATIONS = 30;
    public static final float DEFAULT_MAX_EM_ITERATIONS = 200;
    public static final float DEFAULT_EM_MAX_DELTA_P_FOR_STABLE = .005f;
    public static final int DEFAULT_EM_MAX_ITERATIONS_STABLE_FOR_CONVERGENCE = 1;

    //minimum match probability to keep in output
    protected float minMatchProbability;

    public static final float KS_CUTOFF_FOR_WARN = 0.005f;


    //Default minimum match probability to keep.  Because ProteinProphet uses 0.05 and above.
    public static final float DEFAULT_MIN_MATCH_PROBABILITY = 0.1f;                    

    //maximum depth of the quadtree.  We have to set a maximum or we run out of
    //precision, even when using doubles.
    public static final int MAX_TREE_DEPTH = 30;

    //Scaling factor for error values.  This is purely cosmetic.
    public static final int SCALING_FACTOR = 100;

    //The number of points to find around each point in order to estimate the area.
    //Density will always be estimated using this number of points unless there _aren't_ that
    //many points in the distribution
    public static final int NUM_TARGET_POINTS = 30;
    public static final int NUM_DECOY_POINTS = 30;


    //these statistics are kept for methods development only, controlled by the keepStatistics flag.
    //For heaven's sake, turn this flag off in the shipped code.
    protected boolean keepStatistics = false;

    protected List<Double> targetAreas = new ArrayList<Double>();
    protected List<Double> decoyAreas = new ArrayList<Double>();

    protected List<Integer> targetWindowFeatureNumbers = new ArrayList<Integer>();
    protected List<Integer> decoyWindowFeatureNumbers = new ArrayList<Integer>();

    protected float initialProportionTrue = 0f;
    protected float meanProbability = 0f;
    protected float expectedTrue = 0f;

    protected float ks_score_x = 0f;
    protected float ks_score_y = 0f;

    protected float quantileCorrX = 0f;
    protected float quantileCorrY = 0f;

    protected float quantileBetaX = 0f;
    protected float quantileBetaY = 0f;

    protected float mu_x = 0f;
    protected float mu_y = 0f;

    protected float sigma_x = 0f;
    protected float sigma_y = 0f;

    protected int num_iterations = 0;

    protected float proportion = 0f;



    /**
     * Set the loose matching parameters.  Possibly should just specify the target and decoy
     * matching results here, too.
     * @param minDeltaMass
     * @param maxDeltaMass
     * @param minDeltaElution
     * @param maxDeltaElution
     * @param minMatchProbability
     */
    public AmtMatchProbabilityAssigner(float minDeltaMass, float maxDeltaMass,
                                       float minDeltaElution, float maxDeltaElution,
                                       float minMatchProbability)
    {
        this.minDeltaMass = minDeltaMass;
        this.maxDeltaMass = maxDeltaMass;
        this.minDeltaElution = minDeltaElution;
        this.maxDeltaElution = maxDeltaElution;

        //this relies on maxDeltaMass > 0 and minDeltaMass < 0, etc.
        totalMassRange = maxDeltaMass + Math.abs(minDeltaMass);
        totalElutionRange = maxDeltaElution + Math.abs(minDeltaElution);

        this.minMatchProbability = minMatchProbability;
    }


    /**
     * Helper method to analyze a matchingresult and create the error data, both
     * dimensions, that is fed the probability model.
     * The data structure that this returns is atrocious, I know.  It returns
     * the list of mass errors, the list of H errors, and a map from pairs of features
     * in the matchingResult to indices in the lists.
     * @param matchingResult
     * @return
     */
    Pair<Pair<List<Float>, List<Float>>,Map<Pair<Feature,Feature>, Integer>>
          createMassAndHErrorLists(
            AmtFeatureSetMatcher.FeatureMatchingResult matchingResult)
    {
       List<Float> massErrorDataList = new ArrayList<Float>();
       List<Float> hErrorDataList = new ArrayList<Float>();
        Map<Pair<Feature,Feature>, Integer> amtMatchErrorIndexMap =
                new HashMap<Pair<Feature,Feature>, Integer>();

        for (Feature ms1Feature : matchingResult.getMasterSetFeatures())
        {
            for (Feature matchedAmtFeature : matchingResult.get(ms1Feature))
            {
                amtMatchErrorIndexMap.put(new Pair<Feature,Feature>(ms1Feature, matchedAmtFeature),
                                          massErrorDataList.size());

                //convert to ppm
                massErrorDataList.add((ms1Feature.getMass() - matchedAmtFeature.getMass()) *
                    (1000000 / ms1Feature.getMass()));
                hErrorDataList.add((float) (AmtExtraInfoDef.getObservedHydrophobicity(ms1Feature) -
                            (AmtExtraInfoDef.getObservedHydrophobicity(matchedAmtFeature))));
            }
        }
        Pair<List<Float>, List<Float>> errorLists =
                new Pair<List<Float>, List<Float>>(massErrorDataList, hErrorDataList);
        return new Pair<Pair<List<Float>, List<Float>>,Map<Pair<Feature,Feature>, Integer>>(
                errorLists, amtMatchErrorIndexMap);
    }

    /**
     * Use the initial (loose-tolerance) matching data, to both the target and decoy databases,
     * to determine a probability for each loose match.  Probabilities get assigned directly
     * to the MS1 features.  This method also makes the peptide assignments:  one peptide per
     * MS1 feature, break ties using probability of match.
     *
     * Probability for each match is
     * (estimated target density - estimated decoy density) / estimated target density
     *
     * @param targetMatchingResult
     * @param decoyMatchingResult
     * @param showCharts
     * @throws IOException
     */
    public List<Feature> assignMatchesAndProbabilities(
            AmtFeatureSetMatcher.FeatureMatchingResult targetMatchingResult,
            AmtFeatureSetMatcher.FeatureMatchingResult decoyMatchingResult,
            boolean showCharts)
                throws IOException
    {
        //Create lists of error data and auxiliary data structures for target matches
        Pair<Pair<List<Float>, List<Float>>,Map<Pair<Feature,Feature>, Integer>>
                targetErrorDataListsAndMap =
                    createMassAndHErrorLists(targetMatchingResult);


        Pair<List<Float>, List<Float>> targetErrorDataLists =
                targetErrorDataListsAndMap.first;
        Map<Pair<Feature,Feature>, Integer> targetAmtMatchErrorIndexMap =
                targetErrorDataListsAndMap.second;

        List<Float> targetMassErrorList = targetErrorDataLists.first;
        List<Float> targetHErrorList = targetErrorDataLists.second;

        int numTargetPoints = targetMassErrorList.size();


        //Create lists of error data for decoy matches
        Pair<Pair<List<Float>, List<Float>>,Map<Pair<Feature,Feature>, Integer>>
                decoyErrorDataListsAndMap =
                createMassAndHErrorLists(decoyMatchingResult);
        Pair<List<Float>, List<Float>> decoyErrorDataLists =
                decoyErrorDataListsAndMap.first;

        List<Float> decoyMassErrorList = decoyErrorDataLists.first;
        List<Float> decoyHErrorList = decoyErrorDataLists.second;

        int numDecoyPoints = decoyMassErrorList.size();

        if (showCharts)
        {
            PanelWithScatterPlot pspDecoy =
                    new PanelWithScatterPlot(decoyHErrorList, decoyMassErrorList,
                                             "Decoy data");
            pspDecoy.setPointSize(2);
            pspDecoy.setAxisLabels("deltaNRT (NRT units)", "deltaMass (ppm)");
            pspDecoy.displayInTab();
        }        

        _log.debug("Target matches: " + targetMatchingResult.size() + ", data points: " + numTargetPoints);
        _log.debug("\tDecoy matches: " + decoyMatchingResult.size() + ", decoy data points: " + numDecoyPoints);

        ApplicationContext.setMessage("Starting probability assignment...");
        //calculate the probabilities.  This can take a while, so track how long.
        Date beforeProbDate = new Date();
        float[] allMatchProbabilities = null;

        initialProportionTrue = (float) (numTargetPoints - numDecoyPoints) / (float) numTargetPoints;
        //fudge in case of no advantage for target.  This is to allow decoy matches
        if (initialProportionTrue <= 0)
        {
            ApplicationContext.setMessage("WARNING: Adjusting initial proportion true from " + initialProportionTrue +
                    " to 0.001.  This should only be necessary for a decoy match.  If you are not performing a decoy " +
                    "match, this message indicates that matching has performed VERY POORLY: you have at least as " +
                    "many decoy matches as target matches.");
            initialProportionTrue = 0.001f;
        }

        ApplicationContext.setMessage("\tinitial proportion true: " + initialProportionTrue);
        ApplicationContext.setMessage("Calculating probabilities with EM (parametric) method...");

        allMatchProbabilities =
                calculateProbabilitiesEM(targetMassErrorList, targetHErrorList,
                        initialProportionTrue, showCharts);

        ApplicationContext.setMessage("Done.");


        Date afterProbDate = new Date();
        long probMillis = afterProbDate.getTime() - beforeProbDate.getTime();
        ApplicationContext.setMessage("Probability assignment took " + ((float) probMillis / 1000.0f) +
                                       " seconds");

        //assign probabilities -- and peptides -- to features.  If there are multiple matches per feature,
        //assign the highest-probability match
        List<Float> featureMatchProbs = new ArrayList<Float>();
        int numPosProbMatches=0;
        int numPoint95Matches=0;

        //for each observation, the /sum/ of the probability of all matches made
        float[] featureProbabilitySums = new float[targetMatchingResult.size()];
        float[] featureProbabilities = new float[targetMatchingResult.size()];
        float[] featureSecondBestProbabilities = new float[targetMatchingResult.size()];

        List<Feature> matchedMS1Features = new ArrayList<Feature>();
        int ms1FeatureIndex=0;
        for (Feature ms1Feature : targetMatchingResult.getMasterSetFeatures())
        {
            List<Feature> matchedAmtFeatures = targetMatchingResult.get(ms1Feature);
            Feature bestAmtMatchFeature = matchedAmtFeatures.get(0);
            float bestMatchProbThisFeature = -1f;

            Map<String, Float> bestMatchProbsPerPeptide = new HashMap<String,Float>();
            //only use the best AMT match.  This is incomplete, of course.
            for (Feature amtFeature : matchedAmtFeatures)
            {
                int probabilityIndex =
                        targetAmtMatchErrorIndexMap.get(new Pair<Feature,Feature>(ms1Feature, amtFeature));
                float matchProbability =
                        allMatchProbabilities[probabilityIndex];

                String peptide = MS2ExtraInfoDef.getFirstPeptide(amtFeature);
                Float previousPeptideBest = bestMatchProbsPerPeptide.get(peptide);
                if (previousPeptideBest == null)
                    previousPeptideBest = 0f;

                if (matchProbability > previousPeptideBest)
                    bestMatchProbsPerPeptide.put(peptide, matchProbability);


                if (matchProbability > bestMatchProbThisFeature)
                {
                    bestMatchProbThisFeature = matchProbability;
                    bestAmtMatchFeature = amtFeature;
                }
            }

            for (String matchedPeptide : bestMatchProbsPerPeptide.keySet())
            {
                float peptideProb = bestMatchProbsPerPeptide.get(matchedPeptide);
                featureProbabilitySums[ms1FeatureIndex] += peptideProb;
                if (peptideProb < bestMatchProbThisFeature &&
                    peptideProb > featureSecondBestProbabilities[ms1FeatureIndex])
                {
                        featureSecondBestProbabilities[ms1FeatureIndex] = peptideProb;
                }
            }

            featureProbabilities[ms1FeatureIndex] = bestMatchProbThisFeature;

            if (bestMatchProbThisFeature <= 0)
            {
                continue;
            }

            numPosProbMatches++;
            if (bestMatchProbThisFeature > 0.95)
                numPoint95Matches++;

            if (bestMatchProbThisFeature >= minMatchProbability)
            {
                MS2ExtraInfoDef.setSinglePeptide(ms1Feature,
                        MS2ExtraInfoDef.getFirstPeptide(bestAmtMatchFeature));
                
                List<ModifiedAminoAcid>[] modifiedAAs =
                        MS2ExtraInfoDef.getModifiedAminoAcids(bestAmtMatchFeature);
                if (modifiedAAs != null)
                {
                    MS2ExtraInfoDef.setModifiedAminoAcids(ms1Feature, modifiedAAs);
                }

                //probability that the match is correct
                float amtMatchProbability = bestMatchProbThisFeature;
                AmtExtraInfoDef.setMatchProbability(ms1Feature, amtMatchProbability);

                //probability that the ID in the database is correct -- this is stored on the AMT feature,
                //modeled for now as PeptideProphet.  I'm hedging my bets in case somehow that PeptideProphet
                //value didn't get assigned
                float idProbability = 1;
                if (MS2ExtraInfoDef.hasPeptideProphet(bestAmtMatchFeature) &&
                    MS2ExtraInfoDef.getPeptideProphet(bestAmtMatchFeature) > 0)
                    idProbability = (float) MS2ExtraInfoDef.getPeptideProphet(bestAmtMatchFeature);

                //This represents the overall probability of correct ID:
                //match probability times ID probability
                float probabilityToAssign = amtMatchProbability * idProbability;
                MS2ExtraInfoDef.setPeptideProphet(ms1Feature, probabilityToAssign);

                matchedMS1Features.add(ms1Feature);
            }

            featureMatchProbs.add(bestMatchProbThisFeature);
            ms1FeatureIndex++;
        }

        meanProbability = BasicStatistics.mean(featureMatchProbs);
        expectedTrue = meanProbability * featureMatchProbs.size();
        ApplicationContext.infoMessage("Actual matches made with prob>0: " + numPosProbMatches +
                ", with prob>.95: " + numPoint95Matches +
                ", mean probability = " + meanProbability +
                ", expected true = " + expectedTrue);



        //two color-coded charts -- one for all matches, one for everything with p>=.9
        if (showCharts)
        {
//            PanelWithHistogram pwh = new PanelWithHistogram(featureProbabilitySums);
//            pwh.displayDialog("probability /sums/");
//
//
//            ScatterPlotDialog spdProbSum = new ScatterPlotDialog(featureProbabilities,featureProbabilitySums,
//                    "probability sums vs. probabilities");
//            spdProbSum.setVisible(true);
//
//            ScatterPlotDialog spdSecondBest = new ScatterPlotDialog(featureProbabilities,featureSecondBestProbabilities,
//                    "Second best prob vs. best prob");
//            spdSecondBest.setVisible(true);

            PanelWithScatterPlot psp = new PanelWithScatterPlot();
            psp.setPointSize(2);
            psp.setAxisLabels("deltaNRT (NRT units)", "deltaMass (ppm)");
            psp.setName("All probabilities");

            int numGroups = 10;
            for (int j=0; j<numGroups; j++)
            {
                float minProbThisGroup = (float)j/(float)numGroups;
                float maxProbThisGroup = (float)(j+1)/(float)numGroups;
                int red = (255/numGroups) * j;
                int blue = 255 - (255/numGroups) * j;
                Color color = new Color(blue, 10,red);

                List<Float> thisGroupMass = new ArrayList<Float>();
                List<Float> thisGroupH = new ArrayList<Float>();

                for (int k=0; k<numTargetPoints; k++)
                {
                    if (allMatchProbabilities[k] <= maxProbThisGroup &&
                        allMatchProbabilities[k] >= minProbThisGroup)
                    {
                        thisGroupMass.add(targetMassErrorList.get(k));
                        thisGroupH.add(targetHErrorList.get(k));
                    }
                }

                psp.addData(thisGroupH, thisGroupMass, ""+minProbThisGroup);
                psp.setSeriesColor(j, color);
                psp.setAxisLabels("deltaNRT (NRT units)", "deltaMass (ppm)");                
                psp.setPointSize(2);
            }
            psp.displayInTab();

//            PanelWithScatterPlot psp2 = new PanelWithScatterPlot();
//            psp2.setName("Probabilities >=.9");
//            int numHighProbGroups = 5;
//            for (int j=0; j<numHighProbGroups; j++)
//            {
//                float minProbThisGroup = 0.9f + (0.1f * (float)j/(float)numHighProbGroups);
//                float maxProbThisGroup = 0.9f + (0.1f * (float)(j+1)/(float)numHighProbGroups);
//                int red = (255/numHighProbGroups) * j;
//                int blue = 255 - red;
//                Color color = new Color(blue, 10,red);
//
//                List<Float> thisGroupMass = new ArrayList<Float>();
//                List<Float> thisGroupH = new ArrayList<Float>();
//
//                for (int k=0; k<numTargetPoints; k++)
//                {
//                    if (allMatchProbabilities[k] <= maxProbThisGroup &&
//                        allMatchProbabilities[k] >= minProbThisGroup)
//                    {
//                        thisGroupH.add(targetHErrorList.get(k));
//                        thisGroupMass.add(targetMassErrorList.get(k));
//                    }
//                }
//
//                psp2.addData(thisGroupH, thisGroupMass, ""+minProbThisGroup);
//                psp2.setSeriesColor(j, color);
//                psp2.setPointSize(2);
//            }
//            psp2.displayInTab();
        }

        return matchedMS1Features;
    }




    /**
     * Use the Expectation Maximization algorithm to determine match probabilities by
     * modeling the true hits as a normal distribution, and the false hits as a uniform
     * distribution, superimposed in the target distribution.
     * @param targetMassErrorDataList
     * @param targetHErrorDataList
     * @param proportionTrue
     * @param showCharts
     * @return
     * @throws IOException
     */
    public float[] calculateProbabilitiesEM(List<Float> targetMassErrorDataList,
                                         List<Float> targetHErrorDataList,
                                         float proportionTrue,
                                         boolean showCharts)
            throws IOException
    {
        int numPoints = targetMassErrorDataList.size();

        double[] targetMassErrorData = new double[numPoints];
        double[] targetHErrorData = new double[numPoints];



        for (int i=0; i<numPoints; i++)
        {
            targetHErrorData[i] = targetHErrorDataList.get(i);
            targetMassErrorData[i] = targetMassErrorDataList.get(i);
        }


        Map<String,Object> rScalarVarMap = new HashMap<String,Object>();
        Map<String,double[]> rVectorVarMap = new HashMap<String,double[]>();



        rVectorVarMap.put("targetx",targetHErrorData);
        rVectorVarMap.put("targety",targetMassErrorData);


        rScalarVarMap.put("proportion",(double) proportionTrue);
        double area = (maxDeltaMass - minDeltaMass) * (maxDeltaElution - minDeltaElution);
        rScalarVarMap.put("area",area);
        rScalarVarMap.put("miniterations",(double) DEFAULT_MIN_EM_ITERATIONS);
        rScalarVarMap.put("maxiterations",(double) DEFAULT_MAX_EM_ITERATIONS);
        rScalarVarMap.put("max_deltap_proportion_for_stable",(double) DEFAULT_EM_MAX_DELTA_P_FOR_STABLE);
        rScalarVarMap.put("iters_stable_for_converg",(double) DEFAULT_EM_MAX_ITERATIONS_STABLE_FOR_CONVERGENCE);

        rScalarVarMap.put("showcharts",showCharts ? "TRUE" : "FALSE");
        File outChartFile = null;
        File normTestOutChartFile = null;

        if (showCharts)
        {
            outChartFile = TempFileManager.createTempFile("em_plots.jpg", this);
            normTestOutChartFile = TempFileManager.createTempFile("em_normtest_plots.jpg", this);

            rScalarVarMap.put("chart_out_file", "'" + RInterface.generateRFriendlyPath(outChartFile) + "'");
            rScalarVarMap.put("normtest_chart_out_file", "'" +
                    RInterface.generateRFriendlyPath(normTestOutChartFile) + "'");

        }

        String calculateProbabilitiesCommand =
          RInterface.readResourceFile("/org/fhcrc/cpl/viewer/amt/assign_amt_probabilities_em.R");

        //this timeout is arbitrary and dangerous.  Woo hoo!
        int timeoutMilliseconds=maxRProbAssignmentMillis;
        String rResult = RInterface.evaluateRExpression(calculateProbabilitiesCommand,
                rScalarVarMap, rVectorVarMap, null, null, timeoutMilliseconds);

        Map<String, String> varResultStringMap =
                RInterface.extractVariableStringsFromListOutput(rResult);
        float[] probabilities = new float[numPoints];

        int probsIndex = 0;
        String[] probChunks = varResultStringMap.get("probs").split("\\s");
        for (String probChunk : probChunks)
        {
            if (!probChunk.contains("[") && probChunk.length() > 0)
            {
                probabilities[probsIndex++] = Float.parseFloat(probChunk);
            }
        }
        if (probsIndex != numPoints)
            throw new IOException("FAILED to read probabilities correctly back from R!");

        boolean converged = varResultStringMap.get("converged").contains("TRUE");
        if (converged)
        {
            String numIterString = varResultStringMap.get("num_iterations");
            numIterString = numIterString.substring(numIterString.indexOf("]") + 1).trim();
            num_iterations = Integer.parseInt(numIterString);
            ApplicationContext.setMessage("EM estimation converged after " +
                       num_iterations + " iterations");            
        }
        else
        {
            ApplicationContext.infoMessage("WARNING!!! EM estimation failed to converge after " +
                       DEFAULT_MAX_EM_ITERATIONS + " iterations");
        }

        String[] ksChunks = varResultStringMap.get("ksresults").split("\\s");
        ks_score_x = Float.parseFloat(ksChunks[2]);
        ks_score_y = Float.parseFloat(ksChunks[3]);

        //todo: move parsing of vector results into RInterface
        String[] distParamValues = varResultStringMap.get("dist_params").split("\\s");
        List<Float> paramVals = new ArrayList<Float>();
        for (String paramVal : distParamValues)
            if (paramVal != null && paramVal.length() > 1 && !paramVal.contains("["))
                paramVals.add(Float.parseFloat(paramVal));

        mu_x = paramVals.get(0);
        mu_y = paramVals.get(1);
        sigma_x = paramVals.get(2);
        sigma_y = paramVals.get(3);
        proportion = paramVals.get(4);


        _log.debug("Distribution params: mu_x=" + mu_x + ", mu_y=" + mu_y +
                   ", sigma_x=" + sigma_x + ", sigma_y=" + sigma_y + ", proportion=" + proportion);


        if (ks_score_x < KS_CUTOFF_FOR_WARN|| ks_score_y < KS_CUTOFF_FOR_WARN)
        {
            _log.debug("WARNING!!!!  Kolmogorov-Smirnov test indicates that these matching data " +
                "may not conform to a bivariate normal distribution intermingled with a uniform " +
                "distribution.  If this key assumption fails, match probabilities will not be " +
                "accurate.  Please consider re-running this analysis in non-parametric mode. " +
                "KS p-values: X=" + ks_score_x + ", Y=" + ks_score_y);
        }
        else
        {
            ApplicationContext.setMessage("KS normality test passed.  KS values: x = " + ks_score_x + ", y = " + ks_score_y);
        }

        String[] corrChunks = varResultStringMap.get("corresults").split("\\s");
        quantileCorrX = Float.parseFloat(corrChunks[2]);
        quantileCorrY = Float.parseFloat(corrChunks[3]);

        String[] qBetaChunks = varResultStringMap.get("qbetas").split("\\s");
        quantileBetaX = Float.parseFloat(qBetaChunks[2]);
        quantileBetaY = Float.parseFloat(qBetaChunks[3]);        

        if (showCharts)
        {
            try
            {
                PanelWithBlindImageChart pwbic =
                        new PanelWithBlindImageChart(outChartFile,"EM Parameters");
                pwbic.displayInTab();

            }
            catch (Exception e)
            {
                ApplicationContext.errorMessage("Error displaying error cutoff chart images, file: "
                              + outChartFile.getAbsolutePath(),e);
            }
            try
            {
                PanelWithBlindImageChart pwbic =
                        new PanelWithBlindImageChart(normTestOutChartFile,"EM dist analysis");
                pwbic.displayInTab();
            }
            catch (Exception e)
            {
                ApplicationContext.infoMessage("Error displaying error cutoff chart images, file: "
                              + normTestOutChartFile.getAbsolutePath() + ", error: " + e.getMessage());
            }


            //perspective plot, with distributions indicated
            try
            {
                double minH = Double.MAX_VALUE;
                double maxH = Double.MIN_VALUE;
                double minMass = Double.MAX_VALUE;
                double maxMass = Double.MIN_VALUE;

                for (int i=0; i<targetHErrorData.length; i++)
                {
                    minH = Math.min(minH, targetHErrorData[i]);
                    maxH = Math.max(maxH, targetHErrorData[i]);
                    minMass = Math.min(minMass, targetMassErrorData[i]);
                    maxMass = Math.max(maxMass, targetMassErrorData[i]);
                }
                int numBinsEachDim = 25;

                double rangeH = maxH - minH;
                double rangeMass = maxMass - minMass;
                double binSizeH = rangeH / numBinsEachDim;
                double binSizeMass = rangeMass / numBinsEachDim;

                _log.debug("Building perspective chart...");
                PanelWithRPerspectivePlot persp =
                        new PanelWithRPerspectivePlot();
                persp.setMillisToWait(300000);
                persp.setName("Distribution");
                persp.setRotationAngle(30);
                persp.setChartWidth(1100);
                persp.setChartHeight(1100);
                persp.setAxisRVariableNames("HError","MassError","Count");
                persp.setForegroundColorString("lightblue");

                double unifHeight = (targetHErrorData.length * (1 - proportion)) /
                                    (numBinsEachDim * numBinsEachDim);
                _log.debug("Uniform density height: " + unifHeight);

                int numDistLines = 50;
                int numDistLinePoints = 125;

                double expectedNumTrue = numPoints * proportion;

                double distanceBetweenDistLinePointsH = rangeH / (numDistLinePoints -1);
                double distanceBetweenDistLinePointsMass = rangeMass / (numDistLinePoints -1);
                double distanceBetweenDistLinesH = rangeH / (numDistLines -1);
                double distanceBetweenDistLinesMass = rangeMass / (numDistLines -1);

                for (int i=0; i<numDistLines; i++)
                {
                    double[] distLine1x = new double[numDistLinePoints];
                    double[] distLine1y = new double[numDistLinePoints];
                    double[] distLine1z = new double[numDistLinePoints];

                    double[] distLine2x = new double[numDistLinePoints];
                    double[] distLine2y = new double[numDistLinePoints];
                    double[] distLine2z = new double[numDistLinePoints];
                    for (int j=0; j<numDistLinePoints; j++)
                    {
                        distLine1x[j] = minH + (i * distanceBetweenDistLinesH);
                        distLine1y[j] = minMass + (j * distanceBetweenDistLinePointsMass);
                        distLine1z[j] = calculateLocalNormalizedDensity(distLine1x[j], distLine1y[j],
                                        expectedNumTrue, binSizeH, binSizeMass) * expectedNumTrue;
                        distLine1z[j] += unifHeight;

//System.err.println("xx=" + distLine1x[j] + ", y=" + distLine1y[j] + ", z=" + distLine1z[j]);
                        distLine2x[j] = minH + (j * distanceBetweenDistLinePointsH);
                        distLine2y[j] = minMass + (i * distanceBetweenDistLinesMass);
                        distLine2z[j] = calculateLocalNormalizedDensity(distLine2x[j], distLine2y[j],
                                        expectedNumTrue, binSizeH, binSizeMass) * expectedNumTrue;
                        distLine2z[j] += unifHeight;

                    }
                    persp.addLine(distLine1x, distLine1y, distLine1z, "red");
                    persp.addLine(distLine2x, distLine2y, distLine2z, "red");
                }


//add lines representing uniform and normal distributions
                persp.plotPointsSummary(targetHErrorData, targetMassErrorData, binSizeH, binSizeMass);

                persp.displayInTab();
                _log.debug("Done building perspective chart");
            }
            catch (Exception e)
            {
                ApplicationContext.infoMessage("Error displaying perspective plot: " + e.getMessage());
                e.printStackTrace(System.err);
            }

        }
        TempFileManager.deleteTempFiles(this);
        
        return probabilities;
    }


    /**
     * Use the normal CDF to calculate the density of a small area of the distribution
     * @param xpos
     * @param ypos
     * @param expectedNumTrue
     * @param binSizeX
     * @param binSizeY
     * @return
     */
    protected double calculateLocalNormalizedDensity(double xpos, double ypos, double expectedNumTrue,
                                                     double binSizeX, double binSizeY)
    {
        double proportionX = Math.abs(BasicStatistics.calcNormalCumDensity(mu_x, sigma_x, xpos) -
                             BasicStatistics.calcNormalCumDensity(mu_x, sigma_x, xpos + binSizeX));
        double proportionY = Math.abs(BasicStatistics.calcNormalCumDensity(mu_y, sigma_y, ypos ) -
                             BasicStatistics.calcNormalCumDensity(mu_y, sigma_y, ypos + binSizeY));

        return proportionX * proportionY;
    }



    public float getInitialProportionTrue()
    {
        return initialProportionTrue;
    }

    public void setInitialProportionTrue(float initialProportionTrue)
    {
        this.initialProportionTrue = initialProportionTrue;
    }


    public float getExpectedTrue()
    {
        return expectedTrue;
    }


    public float getKsScoreX()
    {
        return ks_score_x;
    }

    public float getKsScoreY()
    {
        return ks_score_y;
    }

    public float getQuantileCorrX()
    {
        return quantileCorrX;
    }

    public float getQuantileCorrY()
    {
        return quantileCorrY;
    }


    public float getQuantileBetaX()
    {
        return quantileBetaX;
    }

    public float getQuantileBetaY()
    {
        return quantileBetaY;
    }

    public int getMaxRProbAssignmentMillis()
    {
        return maxRProbAssignmentMillis;
    }

    public void setMaxRProbAssignmentMillis(int maxRProbAssignmentMillis)
    {
        this.maxRProbAssignmentMillis = maxRProbAssignmentMillis;
    }


    public float getMuX()
    {
        return mu_x;
    }

    public float getMuY()
    {
        return mu_y;
    }

    public float getSigmaX()
    {
        return sigma_x;
    }

    public float getSigmaY()
    {
        return sigma_y;
    }

    public int getNumIterations()
    {
        return num_iterations;
    }
}
