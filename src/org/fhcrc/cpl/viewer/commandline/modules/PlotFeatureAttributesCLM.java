/*
 * Copyright (c) 2003-2008 Fred Hutchinson Cancer Research Center
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
package org.fhcrc.cpl.viewer.commandline.modules;

import org.fhcrc.cpl.toolbox.commandline.arguments.*;
import org.fhcrc.cpl.viewer.feature.FeatureSet;
import org.fhcrc.cpl.viewer.feature.Feature;
import org.fhcrc.cpl.viewer.feature.extraInfo.MS2ExtraInfoDef;
import org.fhcrc.cpl.viewer.feature.extraInfo.IsotopicLabelExtraInfoDef;
import org.fhcrc.cpl.toolbox.BasicStatistics;
import org.fhcrc.cpl.toolbox.ApplicationContext;
import org.fhcrc.cpl.toolbox.commandline.CommandLineModuleExecutionException;
import org.fhcrc.cpl.toolbox.commandline.CommandLineModule;
import org.fhcrc.cpl.toolbox.gui.chart.PanelWithHistogram;
import org.fhcrc.cpl.toolbox.gui.chart.PanelWithChart;
import org.fhcrc.cpl.toolbox.gui.chart.PanelWithScatterPlot;
import org.apache.log4j.Logger;


import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;


/**
 * Command linemodule for plotting the mass calibration of a feature file
 */
public class PlotFeatureAttributesCLM extends BaseViewerCommandLineModuleImpl
        implements CommandLineModule
{
    protected static Logger _log = Logger.getLogger(PlotFeatureAttributesCLM.class);

    protected File[] featureFiles;

    protected File outFile;
    protected File outDir;

    protected int breaks=100;

    protected static final int PEPTIDE_PROPHET = 0;
    protected static final int FRACTIONAL_DELTA_MASS = 1;
    protected static final int INTENSITY = 2;
    protected static final int TIME = 3;
    protected static final int RATIO = 4;
    protected static final int LIGHTAREA = 5;
    protected static final int CHARGE= 6;
    protected static final int SUMSQUARES_DISTANCE= 7;
    protected static final int KL= 8;
    protected static final int PEAKS=9;
    protected static final int SEARCHSCORE=10;
    protected static final int FVAL=11;
    protected static final int MASS=12;
    protected static final int MZ=13;



    protected static final int MODE_HISTOGRAM = 0;
    protected static final int MODE_SCATTER = 1;

    //Bit of a hack, of course.
    protected static final float FEATURE_NO_ATTRIBUTE = -9999.93285f;



    protected final static String[] modeStrings =
            {
                    "histogram",
                    "scatter"
            };

    protected final static String[] modeExplanations =
            {
                    "Histogram",
                    "Scatterplot"
            };




    protected final static String[] attrTypeStrings =
            {
                    "pprophet",
                    "fracdeltamass",
                    "intensity",
                    "time",
                    "ratio",
                    "lightarea",
                    "charge",
                    "sumsquaresdist",
                    "kl",
                    "peaks",
                    "searchscore",
                    "fval",
                    "mass",
                    "mz"
            };

    protected final static String[] attrTypeExplanations =
            {
                    "PeptideProphet probabilities",
                    "Fractional Delta Mass",
                    "(Maximum) Intensity",
                    "Retention Time",
                    "Ratio",
                    "Light Area (quantitated peptides)",
                    "Charge",
                    "Sum-of-squares distance",
                    "K/L Score",
                    "Number of peaks",
                    "Search score (must provide score name)",
                    "fval",
                    "mass",
                    "mz"                    
            };

    protected int mode=-1;

    protected int xAttrType=-1;
    protected int yAttrType=-1;

    protected boolean logMode = false;


    protected String searchScoreName = null;

    protected boolean showCharts = true;


    public PlotFeatureAttributesCLM()
    {
        init();
    }

    protected void init()
    {
        mCommandName = "plotfeatureattributes";
        mShortDescription = "Histograms attributes of featuresets";
        mHelpMessage = "asdfasdf";
        CommandLineArgumentDefinition[] argDefs =
                {
                        new EnumeratedValuesArgumentDefinition("attribute",true,attrTypeStrings,
                                attrTypeExplanations),
                        new EnumeratedValuesArgumentDefinition("attribute2",false,attrTypeStrings,
                                attrTypeExplanations),
                        new BooleanArgumentDefinition("logmode",false, "Log mode", logMode),
                        new StringArgumentDefinition("searchscore",false,
                                "Search score name (for searchscore mode)"),
                        createUnnamedSeriesFileArgumentDefinition(
                                true,"Feature file(s)"),
                        new FileToWriteArgumentDefinition("out",false, null),
                        new DirectoryToReadArgumentDefinition("outdir",false, null),
                        new BooleanArgumentDefinition("showcharts", false, "Show charts?", showCharts),
                        new IntegerArgumentDefinition("breaks",false, "Number of breaks", breaks)
                };
        addArgumentDefinitions(argDefs);
    }

    public void assignArgumentValues()
            throws ArgumentValidationException
    {
        if (hasArgumentValue("attribute2"))
            mode = MODE_SCATTER;
        else
            mode = MODE_HISTOGRAM;

        xAttrType = ((EnumeratedValuesArgumentDefinition) getArgumentDefinition("attribute")).getIndexForArgumentValue(getStringArgumentValue("attribute"));

        if (hasArgumentValue("attribute2"))
            yAttrType =  ((EnumeratedValuesArgumentDefinition) getArgumentDefinition("attribute2")).getIndexForArgumentValue(getStringArgumentValue("attribute2"));

        searchScoreName=getStringArgumentValue("searchscore");

        if (xAttrType == SEARCHSCORE || yAttrType == SEARCHSCORE)
            assertArgumentPresent("searchscore");
        else
            assertArgumentAbsent("searchscore");

        featureFiles = this.getUnnamedSeriesFileArgumentValues();
        outFile = getFileArgumentValue("out");
        outDir = getFileArgumentValue("outdir");

        if (featureFiles.length > 1)
            assertArgumentAbsent("out");

        breaks = getIntegerArgumentValue("breaks");

        logMode = getBooleanArgumentValue("logmode");
        showCharts = getBooleanArgumentValue("showcharts");
    }

    protected float getAttributeFromFeature(Feature feature, int attrType)
            throws CommandLineModuleExecutionException
    {
        float result = FEATURE_NO_ATTRIBUTE;

        try
        {
            switch(attrType)
            {
                case PEPTIDE_PROPHET:
                    if (MS2ExtraInfoDef.hasPeptideProphet(feature))
                        result = (float) MS2ExtraInfoDef.getPeptideProphet(feature);
                    break;
                case FRACTIONAL_DELTA_MASS:
                    try
                    {
                        double deltaMass =
                                MS2ExtraInfoDef.getDeltaMass(feature);
                        if (deltaMass < 0)
                            deltaMass = -deltaMass;
                        while (deltaMass > 0.5)
                            deltaMass -= 1;
                        if (deltaMass < 0)
                            deltaMass = -deltaMass;
                        result = (float)deltaMass;
                    }
                    catch (Exception e){}
                    break;
                case INTENSITY:
                    result = feature.getIntensity();
                    break;
                case TIME:
                    result = feature.getTime();
                    break;
                case RATIO:
                    if (IsotopicLabelExtraInfoDef.hasRatio(feature))
                        result =  (float)
                                Math.max(.001,IsotopicLabelExtraInfoDef.getRatio(feature));
                    break;
                case LIGHTAREA:
                    if (IsotopicLabelExtraInfoDef.hasRatio(feature))
                        result =  (float)
                                Math.max(.001,IsotopicLabelExtraInfoDef.getLightIntensity(feature));
                    break;
                case CHARGE:
                    result = (float) feature.getCharge();
                    break;
                case SUMSQUARES_DISTANCE:
                    result =  feature.getSumSquaresDist();
                    break;
                case KL:
                    result =  feature.getKl();
                    break;
                case PEAKS:
                    result = (float) feature.getPeaks();
                    break;
                case SEARCHSCORE:
                    result = Float.parseFloat(MS2ExtraInfoDef.getSearchScore(feature, searchScoreName));
                    break;
                case FVAL:
                    result = (float) MS2ExtraInfoDef.getFval(feature);
                    break;
                case MASS:
                    result = feature.getMass();
                case MZ:
                    result = feature.getMz();
            }

            if (logMode)
                result = (float) Math.log(result);
        }
        catch (Exception e)
        {
            throw new CommandLineModuleExecutionException("Failed to load attribute " + attrType,e);
        }
        return result;
    }

    protected String getAttributeTitle(int attrType)
    {
        String title = "";
        switch(attrType)
        {
            case PEPTIDE_PROPHET:
                title = "PeptideProphet Probabilities";
                break;
            case FRACTIONAL_DELTA_MASS:
                title = "Fractional Delta Masses";
                break;
            case INTENSITY:
                title = "Intensities";
                break;
            case TIME:
                title = "Retention times";
                break;
            case RATIO:
                title = "Ratios";
                break;
            case LIGHTAREA:
                title= "Light Areas";
                break;
            case CHARGE:
                title= "Charge";
                break;
            case SUMSQUARES_DISTANCE:
                title= "Sum-of-squares Distance";
                break;
            case KL:
                title= "K/L Score";
                break;
            case PEAKS:
                title= "Peaks";
                break;
            case SEARCHSCORE:
                title=searchScoreName;
                break;
            case FVAL:
                title="fval";
                break;
        }

        if (logMode)
            title = title + " (log)";
        return title;
    }


    /**
     * do the actual work
     */
    public void execute() throws CommandLineModuleExecutionException
    {
        if (logMode)
            ApplicationContext.infoMessage("Plotting in log mode");
        for (File featureFile : featureFiles)
        {
            ApplicationContext.infoMessage("Processing file " + featureFile.getAbsolutePath());
            processFeatureFile(featureFile);
        }

    }

    protected void processFeatureFile(File featureFile)
            throws CommandLineModuleExecutionException
    {
        FeatureSet featureSet;

        try
        {
            featureSet = new FeatureSet(featureFile);
        }
        catch (Exception e)
        {
            throw new CommandLineModuleExecutionException("Failed to open feature file " + featureFile.getAbsolutePath(), e);
        }
        PanelWithChart panelWithChart = null;
        switch (mode)
        {
            case MODE_HISTOGRAM:
                panelWithChart = buildHistogram(featureSet);
                break;
            case MODE_SCATTER:
                panelWithChart = buildScatter(featureSet);
                break;
        }

        if (showCharts)
            panelWithChart.displayInTab();

        File outputFile = outFile;
        if (outputFile == null && outDir != null)
        {
            String filenameStart = featureFile.getName();
            if (filenameStart.contains("."))
                filenameStart = filenameStart.substring(0, filenameStart.indexOf("."));
            outputFile = new File(outDir, filenameStart + ".chart.png");
        }

        if (outputFile != null)
        {
            try
            {
                panelWithChart.saveChartToImageFile(outputFile);
                System.err.println("Saved output image");
            }
            catch (IOException e)
            {
                throw new CommandLineModuleExecutionException(e);
            }
        }
    }

    protected PanelWithChart buildHistogram(FeatureSet featureSet)
            throws CommandLineModuleExecutionException
    {

        String title = getAttributeTitle(xAttrType);


        List<Float> featureAttributes = new ArrayList<Float>();
        for (int i=0; i<featureSet.getFeatures().length; i++)
        {
            Feature feature = featureSet.getFeatures()[i];

            float xAttr = getAttributeFromFeature(feature, xAttrType);
            if (xAttr != FEATURE_NO_ATTRIBUTE)
                featureAttributes.add(xAttr);
        }
        ApplicationContext.infoMessage("# datapoints: " + featureAttributes.size());
        ApplicationContext.infoMessage("Mean: " + BasicStatistics.mean(featureAttributes));
        ApplicationContext.infoMessage("Median: " + BasicStatistics.median(featureAttributes));


        PanelWithHistogram panelWithHistogram =
                new PanelWithHistogram(featureAttributes, title, breaks);

        if (xAttrType == RATIO)
        {
            int numWithin25Percent=0;
            int numWithin50Percent=0;
            int numWithinTwofold=0;
            int numWithinThreefold=0;
            for (Float logRatio : featureAttributes)
            {
                if (!logMode)
                    logRatio = (float) Math.log(logRatio);
                Float ratioBigOverSmall = (float) Math.exp(Math.abs(logRatio));
                if (ratioBigOverSmall < 3)
                {
                    numWithinThreefold++;
                    if (ratioBigOverSmall < 2)
                    {
                        numWithinTwofold++;
                        if (ratioBigOverSmall < 1.5)
                        {
                            numWithin50Percent++;
                            if (ratioBigOverSmall < 1.25)
                                numWithin25Percent++;
                        }
                    }
                }
            }
            int numRatios = featureAttributes.size();
            int percentWithin25Percent=100 * numWithin25Percent / numRatios;
            int percentWithin50Percent=100 * numWithin50Percent / numRatios;
            int percentWithinTwofold=100 * numWithinTwofold / numRatios;
            int percentWithinThreefold=100 * numWithinThreefold / numRatios;
            ApplicationContext.infoMessage("Ratios within 3fold: " + percentWithinThreefold + "%, 2fold: " +
                    percentWithinTwofold + "%, 50%: " + percentWithin50Percent + "%, 25%: " + percentWithin25Percent + "%");
        }

        return panelWithHistogram;
    }

    protected PanelWithChart buildScatter(FeatureSet featureSet)
            throws CommandLineModuleExecutionException
    {

        String title = getAttributeTitle(xAttrType) + " (x) vs. " + getAttributeTitle(yAttrType) + " (y)";

        List<Float> featureXAttributes = new ArrayList<Float>();
        List<Float> featureYAttributes = new ArrayList<Float>();

        for (int i=0; i<featureSet.getFeatures().length; i++)
        {
            Feature feature = featureSet.getFeatures()[i];
            float xAttr = getAttributeFromFeature(feature, xAttrType);
            float yAttr = getAttributeFromFeature(feature, yAttrType);

            if (xAttr != FEATURE_NO_ATTRIBUTE && yAttr != FEATURE_NO_ATTRIBUTE)
            {
                featureXAttributes.add(getAttributeFromFeature(feature, xAttrType));
                featureYAttributes.add(getAttributeFromFeature(feature, yAttrType));
            }
        }
        ApplicationContext.infoMessage("# datapoints: " + featureXAttributes.size());
        ApplicationContext.infoMessage("X Mean: " + BasicStatistics.mean(featureXAttributes));
        ApplicationContext.infoMessage("X Median: " + BasicStatistics.median(featureXAttributes));
        ApplicationContext.infoMessage("Y Mean: " + BasicStatistics.mean(featureYAttributes));
        ApplicationContext.infoMessage("Y Median: " + BasicStatistics.median(featureYAttributes));


        PanelWithScatterPlot panelWithScatterPlot =
                new PanelWithScatterPlot(featureXAttributes, featureYAttributes, title);

        return panelWithScatterPlot;
    }

}
