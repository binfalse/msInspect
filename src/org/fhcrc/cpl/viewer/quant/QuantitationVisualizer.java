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
package org.fhcrc.cpl.viewer.quant;

import org.fhcrc.cpl.toolbox.commandline.arguments.ArgumentValidationException;
import org.fhcrc.cpl.toolbox.commandline.arguments.CommandLineArgumentDefinition;
import org.fhcrc.cpl.toolbox.gui.chart.*;
import org.fhcrc.cpl.toolbox.gui.HtmlGenerator;
import org.fhcrc.cpl.toolbox.commandline.CommandLineModuleExecutionException;
import org.fhcrc.cpl.toolbox.commandline.CommandLineModule;
import org.fhcrc.cpl.toolbox.commandline.CommandLineModuleUtilities;
import org.fhcrc.cpl.toolbox.ApplicationContext;
import org.fhcrc.cpl.toolbox.Pair;
import org.fhcrc.cpl.viewer.MSRun;
import org.fhcrc.cpl.viewer.feature.filehandler.PepXMLFeatureFileHandler;
import org.fhcrc.cpl.viewer.feature.FeatureSet;
import org.fhcrc.cpl.viewer.feature.Feature;
import org.fhcrc.cpl.viewer.feature.Spectrum;
import org.fhcrc.cpl.viewer.feature.extraInfo.MS2ExtraInfoDef;
import org.fhcrc.cpl.viewer.feature.extraInfo.IsotopicLabelExtraInfoDef;
import org.fhcrc.cpl.viewer.gui.util.PanelWithSpectrumChart;
import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;
import java.awt.*;
import java.awt.image.BufferedImage;


/**
 * test
 */
public class QuantitationVisualizer
{
    protected static Logger _log = Logger.getLogger(QuantitationVisualizer.class);


    protected int resolution = PanelWithSpectrumChart.DEFAULT_RESOLUTION;

    protected Iterator<FeatureSet> featureSetIterator;


    int numHeavyPeaksToPlot = 4;

    protected File outDir = null;

    protected int scanImageHeight = 100;
    protected int maxScansImageHeight = 4000;
    protected int spectrumImageHeight = 800;
    protected int imageWidth = 1200;

    protected File mzXmlDir;

    //The amount by which two features can differ and still be combined into the same single representation.
    //TODO: convert this to log space, parameterize
    protected float maxCombineFeatureRatioDiff = 0.2f;

    protected boolean writeHTMLAndText = true;

    protected float minPeptideProphet = 0;

    protected File mzXmlFile = null;

    protected int numPaddingScans = 3;
    protected float mzPadding = 1;

    protected Set<String> peptidesFound = new HashSet<String>();
    protected int sidebarWidth = 180;

    protected Map<String, Map<String, Map<String, Map<Integer, List<Pair<File, File>>>>>> proteinPeptideFractionChargeFilesMap =
            new HashMap<String, Map<String, Map<String, Map<Integer, List<Pair<File, File>>>>>>();

    protected static final String DUMMY_PROTEIN_NAME = "DUMMY_PROTEIN";



    protected boolean show3DPlots = true;
    protected int rotationAngle3D = PanelWithSpectrumChart.DEFAULT_CONTOUR_PLOT_ROTATION;
    protected int tiltAngle3D = PanelWithSpectrumChart.DEFAULT_CONTOUR_PLOT_TILT;
    protected int imageHeight3D = 1000;
    protected int imageWidth3D = 1000;
    protected boolean show3DAxes = true;

    int scan = 0;


    protected Set<String> peptidesToExamine;
    protected Set<String> proteinsToExamine;
    protected Set<String> fractionsToExamine;


    protected PrintWriter outHtmlPW;
    protected PrintWriter outTsvPW;

    protected boolean showProteinColumn = true;


    protected IOManager ioManager = new IOManager();

    public QuantitationVisualizer()
    {
    }




    /**
     * do the actual work
     */
    public void visualizeQuantEvents() throws CommandLineModuleExecutionException
    {
        File outHtmlFile = new File(outDir,"quantitation.html");
        File outTsvFile = new File(outDir,"quantitation.tsv");


        if (writeHTMLAndText)
        {
            try
            {
                outHtmlPW = new PrintWriter(outHtmlFile);
                outTsvPW = new PrintWriter(outTsvFile);


            }
            catch (IOException e)
            {
                throw new CommandLineModuleExecutionException("ERROR: Failed to open output HTML document");
            }

            ioManager.writeHeader();
        }




        boolean processedAFraction = false;
        while (featureSetIterator.hasNext())
        {
            FeatureSet fraction = featureSetIterator.next();
            ApplicationContext.infoMessage("Handling fraction " + MS2ExtraInfoDef.getFeatureSetBaseName(fraction));
            if (fractionsToExamine == null || fractionsToExamine.contains(MS2ExtraInfoDef.getFeatureSetBaseName(fraction)))
            {
                handleFraction(fraction);
                processedAFraction = true;
            }
        }
        if (!processedAFraction)
            ApplicationContext.infoMessage("WARNING: no fractions processed");
        if (writeHTMLAndText)
        {
            ioManager.writeFooterAndClose();
            ApplicationContext.infoMessage("Saved HTML file " + outHtmlFile.getAbsolutePath());
            ApplicationContext.infoMessage("Saved TSV file " + outTsvFile.getAbsolutePath());
        }

    }

    protected void handleFraction(FeatureSet featureSet)
            throws CommandLineModuleExecutionException
    {
        FeatureSet.FeatureSelector sel = new FeatureSet.FeatureSelector();
        sel.setMinPProphet(minPeptideProphet);
        featureSet = featureSet.filter(sel);

        Arrays.sort(featureSet.getFeatures(), new Feature.ScanAscComparator());

        MSRun run = null;
        File fileToLoad = mzXmlFile;
        if (fileToLoad == null)
        {
            String fractionName = MS2ExtraInfoDef.getFeatureSetBaseName(featureSet);
            try
            {
                fileToLoad = CommandLineModuleUtilities.findFileWithPrefix(fractionName, mzXmlDir, "mzXML");
                ApplicationContext.infoMessage("Found mzXML file " + fileToLoad.getAbsolutePath());
            }
            catch (IOException e)
            {
                throw new CommandLineModuleExecutionException("Couldn't find mzXML file for fraction " +
                        fractionName);
            }
        }

        try
        {
            run = MSRun.load(fileToLoad.getAbsolutePath());
        }
        catch (IOException e)
        {
            throw new CommandLineModuleExecutionException("Failed to load run",e);
        }

        if (proteinsToExamine != null)
            handleProteinsInRun(featureSet, run, proteinsToExamine);
        else if (peptidesToExamine != null)
            handlePeptidesInRun(featureSet, run, peptidesToExamine, DUMMY_PROTEIN_NAME, outDir);
        else
        {
            String fractionName = MS2ExtraInfoDef.getFeatureSetBaseName(featureSet);
            if (fractionsToExamine == null || fractionsToExamine.contains(fractionName))
                handleScanInRun(featureSet, fractionName, run);
        }
    }

    protected void handleScanInRun(FeatureSet featureSet, String fraction, MSRun run)
            throws CommandLineModuleExecutionException
    {
        for (Feature feature : featureSet.getFeatures())
        {
            if (scan == feature.getScan())
                createChartsForEvent(run, outDir, DUMMY_PROTEIN_NAME, fraction, feature);
        }
    }


    protected void handleProteinsInRun(FeatureSet featureSet, MSRun run, Set<String> proteinsToHandle)
            throws CommandLineModuleExecutionException
    {
        for (String protein : proteinsToHandle)
        {
            Set<String> peptidesThisProtein = new HashSet<String>();
            for (Feature feature : featureSet.getFeatures())
            {
                List<String> proteinsThisFeature = MS2ExtraInfoDef.getProteinList(feature);
                if (proteinsThisFeature != null && proteinsThisFeature.contains(protein))
                    peptidesThisProtein.add(MS2ExtraInfoDef.getFirstPeptide(feature));
            }

            if (!peptidesThisProtein.isEmpty())
            {
                ApplicationContext.infoMessage("Protein " + protein + ": finding " + peptidesThisProtein.size() +
                        " peptides in run " + MS2ExtraInfoDef.getFeatureSetBaseName(featureSet));
                File proteinOutDir = new File(outDir, protein);
                proteinOutDir.mkdir();
                handlePeptidesInRun(featureSet, run, peptidesThisProtein, protein, proteinOutDir);
            }
        }
    }

    protected void handlePeptidesInRun(FeatureSet featureSet, MSRun run, Set<String> peptidesToHandle,
                                       String proteinName, File outputDir)
            throws CommandLineModuleExecutionException
    {
        String fraction = MS2ExtraInfoDef.getFeatureSetBaseName(featureSet);



        Map<String, List<Feature>> peptideFeaturesMap = new HashMap<String, List<Feature>>();
        for (Feature feature : featureSet.getFeatures())
        {
            String featurePeptide = MS2ExtraInfoDef.getFirstPeptide(feature);
            if (featurePeptide == null)
                continue;
            if (featurePeptide != null && peptidesToHandle.contains(featurePeptide) &&
                    IsotopicLabelExtraInfoDef.hasRatio(feature))
            {


                if (!peptidesFound.contains(featurePeptide))
                    ApplicationContext.infoMessage("\tFound peptide " + featurePeptide);
                peptidesFound.add(featurePeptide);
                List<Feature> featuresThisPeptide = peptideFeaturesMap.get(featurePeptide);
                if (featuresThisPeptide == null)
                {
                    featuresThisPeptide = new ArrayList<Feature>();
                    peptideFeaturesMap.put(featurePeptide, featuresThisPeptide);
                }
                featuresThisPeptide.add(feature);
            }
        }

        for (String peptide : peptideFeaturesMap.keySet())
        {
            int numTotalEventsThisPeptide = peptideFeaturesMap.get(peptide).size();
            ApplicationContext.infoMessage("\tHandling peptide " + peptide + " with " + numTotalEventsThisPeptide + " events");           
            List<Feature> nonOverlappingFeatures = findNonOverlappingEvents(peptideFeaturesMap.get(peptide));

            for (Feature feature : nonOverlappingFeatures)
            {
                createChartsForEvent(run, outputDir, proteinName, fraction, feature);
            }
        }
    }

    protected List<Feature> findNonOverlappingEvents(List<Feature> features)
    {
        List<Feature> result = new ArrayList<Feature>();
        Map<Integer, List<Feature>> chargeFeatureMap = new HashMap<Integer, List<Feature>>();
        for (Feature feature : features)
        {
            List<Feature> featuresThisCharge = chargeFeatureMap.get(feature.getCharge());
            if (featuresThisCharge == null)
            {
                featuresThisCharge = new ArrayList<Feature>();
                chargeFeatureMap.put(feature.getCharge(), featuresThisCharge);
            }
            featuresThisCharge.add(feature);
        }
        for (List<Feature> featuresWithinCharge : chargeFeatureMap.values())
        {
            while (!featuresWithinCharge.isEmpty())
                result.add(findFeatureRepresentingFirstFeatureOverlap(featuresWithinCharge));
        }
        return result;
    }

    /**
     * Find all features overlapping in scans with the first feature.  Choose on of them to represent them all
     * ("median" by scan).
     * SIDE EFFECT: Remove them all from featuresWithinCharge
     * @param featuresWithinCharge
     * @return
     */
    protected Feature findFeatureRepresentingFirstFeatureOverlap(List<Feature> featuresWithinCharge)
    {
        List<Feature> featuresOverlappingFirst = new ArrayList<Feature>();
        featuresOverlappingFirst.add(featuresWithinCharge.get(0));

        Feature firstFeature = featuresOverlappingFirst.get(0);
        Feature result = firstFeature;
        StringBuffer allScansStringBuf = new StringBuffer("" + firstFeature.getScan());        

        if (featuresWithinCharge.size() > 1)
        {
            int firstFeatureStart = IsotopicLabelExtraInfoDef.getLightFirstScan(firstFeature);
            int firstFeatureEnd = IsotopicLabelExtraInfoDef.getLightLastScan(firstFeature);
            if (firstFeatureStart <= 0)
                firstFeatureStart = firstFeature.getScan();
            if (firstFeatureEnd <= 0)
                firstFeatureEnd = firstFeature.getScan();
            double firstFeatureRatio = IsotopicLabelExtraInfoDef.getRatio(firstFeature);
            for (int i=1; i<featuresWithinCharge.size(); i++)
            {
                Feature compareFeature = featuresWithinCharge.get(i);
                int compareFeatureStart = IsotopicLabelExtraInfoDef.getLightFirstScan(compareFeature);
                int compareFeatureEnd = IsotopicLabelExtraInfoDef.getLightLastScan(compareFeature);
                if (compareFeatureStart <= 0)
                    compareFeatureStart = compareFeature.getScan();
                if (compareFeatureEnd <= 0)
                    compareFeatureEnd = compareFeature.getScan();
                if (Math.max(firstFeatureStart, compareFeatureStart) < Math.min(firstFeatureEnd, compareFeatureEnd) &&
                        Math.abs(IsotopicLabelExtraInfoDef.getRatio(compareFeature) - firstFeatureRatio) <
                                maxCombineFeatureRatioDiff)
                {
                    allScansStringBuf.append(",");
                    allScansStringBuf.append("" + compareFeature.getScan());
                    featuresOverlappingFirst.add(compareFeature);
                }
            }


            //find the "median" feature by scan (round down if even number)
            Collections.sort(featuresOverlappingFirst, new Feature.ScanAscComparator());
            int numOverlapping = featuresOverlappingFirst.size();
            if (numOverlapping % 2 == 1)
                result = featuresOverlappingFirst.get((numOverlapping-1)/2);
            else
                result = featuresOverlappingFirst.get((numOverlapping-2)/2);
        }
        ApplicationContext.infoMessage("\t\tcharge " + result.getCharge() + ", ratio " +
                IsotopicLabelExtraInfoDef.getRatio(result) + ", scans " +
                IsotopicLabelExtraInfoDef.getLightFirstScan(result) + "-" +
                IsotopicLabelExtraInfoDef.getLightLastScan(result) + ", represents " +
                featuresOverlappingFirst.size() + " event(s)");

        featuresWithinCharge.removeAll(featuresOverlappingFirst);
        result.setDescription(allScansStringBuf.toString());
        return result;
    }

    protected void createChartsForEvent(MSRun run,File outputDir, String protein, String fraction, Feature feature)
            throws CommandLineModuleExecutionException
    {
        String peptide = MS2ExtraInfoDef.getFirstPeptide(feature);

        float lightIntensity = (float) IsotopicLabelExtraInfoDef.getLightIntensity(feature);
        float heavyIntensity =                 (float) IsotopicLabelExtraInfoDef.getHeavyIntensity(feature);
        float ratio =                 (float) IsotopicLabelExtraInfoDef.getRatio(feature);
        int charge = feature.getCharge();
        int firstLightQuantScan = IsotopicLabelExtraInfoDef.getLightFirstScan(feature);
        int lastLightQuantScan = IsotopicLabelExtraInfoDef.getLightLastScan(feature);
        int firstHeavyQuantScan = IsotopicLabelExtraInfoDef.getHeavyFirstScan(feature);
        int lastHeavyQuantScan = IsotopicLabelExtraInfoDef.getHeavyLastScan(feature);

        if (firstLightQuantScan <= 0)
        {
            _log.debug("WARNING: no first light quant scan, using feature scan");
            firstLightQuantScan = feature.getScan();
        }
        if (lastLightQuantScan <= 0)
        {
            _log.debug("WARNING: no last light quant scan, using feature scan");
            lastLightQuantScan = feature.getScan();
        }
        if (firstHeavyQuantScan <= 0)
        {
            _log.debug("WARNING: no first heavy quant scan, using feature scan");
            firstHeavyQuantScan = feature.getScan();
        }
        if (lastHeavyQuantScan <= 0)
        {
            _log.debug("WARNING: no last heavy quant scan, using feature scan");
            lastHeavyQuantScan = feature.getScan();
        }

        int minScanIndex = Math.max(Math.abs(run.getIndexForScanNum(Math.min(firstLightQuantScan, firstHeavyQuantScan))) -
                numPaddingScans, 0);
        int maxScanIndex = Math.min(Math.abs(run.getIndexForScanNum(Math.min(lastLightQuantScan, lastHeavyQuantScan))) +
                numPaddingScans, run.getScanCount()-1);

        int minScan = run.getScanNumForIndex(minScanIndex);
        int maxScan = run.getScanNumForIndex(maxScanIndex);

        float lightNeutralMass = (float) IsotopicLabelExtraInfoDef.getLightMass(feature) - Spectrum.HYDROGEN_ION_MASS;
        float heavyNeutralMass = (float) IsotopicLabelExtraInfoDef.getHeavyMass(feature) - Spectrum.HYDROGEN_ION_MASS;

        float lightMz = lightNeutralMass / feature.getCharge() + Spectrum.HYDROGEN_ION_MASS;
        float heavyMz = heavyNeutralMass / feature.getCharge() + Spectrum.HYDROGEN_ION_MASS;
        float minMz = lightMz - mzPadding;
        float maxMz = heavyMz + numHeavyPeaksToPlot / feature.getCharge() + mzPadding;
        _log.debug("Building chart for feature:\n\t" + feature);
        _log.debug("Scan=" + feature.getScan() + ", ratio=" + ratio + ", lightInt=" + lightIntensity + ", heavyInt=" +
                heavyIntensity + ", minScanIndex=" + minScanIndex + ", maxScanIndex=" + maxScanIndex + ", minMz=" +
                minMz + ", maxMz=" + maxMz);

        PanelWithSpectrumChart spectrumPanel =
                new PanelWithSpectrumChart(run, minScan, maxScan, minMz, maxMz, 
                        firstLightQuantScan, lastLightQuantScan, firstHeavyQuantScan, lastHeavyQuantScan,
                        lightMz, heavyMz);
        spectrumPanel.setResolution(resolution);
        spectrumPanel.setGenerateLineCharts(true);
        spectrumPanel.setGenerate3DChart(show3DPlots);
        spectrumPanel.setIdEventScan(feature.getScan());
        spectrumPanel.setIdEventMz(feature.getMz());
        spectrumPanel.setName("Spectrum");
        spectrumPanel.setContourPlotRotationAngle(rotationAngle3D);
        spectrumPanel.setContourPlotTiltAngle(tiltAngle3D);
        spectrumPanel.setContourPlotWidth(imageWidth3D);
        spectrumPanel.setContourPlotHeight(imageHeight3D);
        spectrumPanel.setContourPlotShowAxes(show3DAxes);

        spectrumPanel.generateChart();
        spectrumPanel.setVisible(true);

        DropdownMultiChartDisplayPanel multiChartPanelForScans = new DropdownMultiChartDisplayPanel();
        multiChartPanelForScans.setDisplaySlideshowButton(true);

        spectrumPanel.setSize(new Dimension(imageWidth, spectrumImageHeight));
        spectrumPanel.setMinimumSize(new Dimension(imageWidth, spectrumImageHeight));




        Map<Integer, PanelWithLineChart> scanChartMap = spectrumPanel.getScanLineChartMap();
        List<Integer> allScans = new ArrayList<Integer>(scanChartMap.keySet());
        Collections.sort(allScans);

        for (int scanForChart : allScans)
            multiChartPanelForScans.addChartPanel(scanChartMap.get(scanForChart));

        String filePrefix = peptide + "_" + fraction + "_" + charge + "_" + feature.getScan();
        if (protein != null && !protein.equals(DUMMY_PROTEIN_NAME))
            filePrefix = protein + "_" + filePrefix;

        File outSpectrumFile = new File(outputDir, filePrefix + "_spectrum.png");
        File outScansFile = new File(outputDir, filePrefix + "_scans.png");
        File out3DFile = new File(outputDir, filePrefix + "_3D.png");



        try
        {
            //the call to spectrumPanel.isSpecifiedScanFoundMS1() is a hack to find out the scan level of the ID scan
            saveChartToImageFileWithSidebar(spectrumPanel, outSpectrumFile, sidebarWidth,
                    peptide, charge, lightMz, heavyMz, lightIntensity, heavyIntensity, ratio,
                    firstLightQuantScan, lastLightQuantScan,
                    firstHeavyQuantScan, lastHeavyQuantScan, lightNeutralMass,
                    feature.getScan(), spectrumPanel.isSpecifiedScanFoundMS1() ? 1 : 2);
            ApplicationContext.infoMessage("Wrote spectrum to image " + outSpectrumFile.getAbsolutePath());
        }
        catch (Exception e)
        {
            throw new CommandLineModuleExecutionException("Failed to save image file",e);
        }

        if (show3DPlots)
        {
            try
            {
                //the call to spectrumPanel.isSpecifiedScanFoundMS1() is a hack to find out the scan level of the ID scan
                saveChartToImageFileWithSidebar(spectrumPanel.getContourPlot(), out3DFile, sidebarWidth,
                        peptide, charge, lightMz, heavyMz, lightIntensity, heavyIntensity, ratio,
                        firstLightQuantScan, lastLightQuantScan,
                        firstHeavyQuantScan, lastHeavyQuantScan, lightNeutralMass,
                        feature.getScan(), spectrumPanel.isSpecifiedScanFoundMS1() ? 1 : 2);
                ApplicationContext.infoMessage("Wrote 3D plot to image " + out3DFile.getAbsolutePath());
            }
            catch (Exception e)
            {
                throw new CommandLineModuleExecutionException("Failed to save 3D image file",e);
            }
        }

        try
        {
            spectrumPanel.savePerScanSpectraImage(imageWidth, scanImageHeight,
                    maxScansImageHeight, outScansFile);
            ApplicationContext.infoMessage("Wrote scans to image " + outScansFile.getAbsolutePath());
        }
        catch (IOException e)
        {
            throw new CommandLineModuleExecutionException("Failed to write image file " +
                    outScansFile.getAbsolutePath(),e);
        }

        String outChartsRelativeDirName = "";
        if (proteinsToExamine != null)
            outChartsRelativeDirName = protein + File.separatorChar;
        QuantEventInfo quantEvent = new QuantEventInfo(protein,  peptide,  fraction,  charge,  feature.getScan(),
                               outSpectrumFile,
                               outScansFile,
                               out3DFile,
                               ratio,  lightIntensity,  heavyIntensity,
                               firstLightQuantScan,  lastLightQuantScan,
                               firstHeavyQuantScan,  lastHeavyQuantScan,
                               feature.getDescription());
        if (writeHTMLAndText)
        {
            outHtmlPW.println(quantEvent.createOutputRowHtml(outChartsRelativeDirName, showProteinColumn, show3DPlots));
            outTsvPW.println(quantEvent.createOutputRowTsv(showProteinColumn, show3DPlots));
        }

        
        //record event
        Map<String, Map<String, Map<Integer, List<Pair<File, File>>>>> peptideFractionChargeFilesMap =
                proteinPeptideFractionChargeFilesMap.get(protein);
        if (peptideFractionChargeFilesMap == null)
        {
            peptideFractionChargeFilesMap = new HashMap<String, Map<String, Map<Integer, List<Pair<File, File>>>>>();
            proteinPeptideFractionChargeFilesMap.put(protein, peptideFractionChargeFilesMap);
        }
        Map<String, Map<Integer, List<Pair<File, File>>>> fractionChargeFilesMap =
                peptideFractionChargeFilesMap.get(peptide);
        if (fractionChargeFilesMap == null)
        {
            fractionChargeFilesMap = new HashMap<String, Map<Integer, List<Pair<File, File>>>>();
            peptideFractionChargeFilesMap.put(peptide, fractionChargeFilesMap);
        }
        Map<Integer, List<Pair<File, File>>> chargeFilesMap = fractionChargeFilesMap.get(fraction);
        if (chargeFilesMap == null)
        {
            chargeFilesMap = new HashMap<Integer, List<Pair<File, File>>>();
            fractionChargeFilesMap.put(fraction, chargeFilesMap);
        }
        List<Pair<File, File>> filesList = chargeFilesMap.get(charge);
        if (filesList == null)
        {
            filesList = new ArrayList<Pair<File, File>>();
            chargeFilesMap.put(charge, filesList);
        }
        filesList.add(new Pair<File, File>(outSpectrumFile, outScansFile));
    }

    public void saveChartToImageFileWithSidebar(PanelWithChart chartPanel,
                                                File outFile, int sidebarWidth,
                                                String peptide, int charge, float lightMz, float heavyMz,
                                                float lightIntensity, float heavyIntensity, float ratio,
                                                int lightMinQuantScan, int lightMaxQuantScan,
                                                int heavyMinQuantScan, int heavyMaxQuantScan,
                                                float lightMass,
                                                int idScan, int idScanLevel) throws IOException
    {
        BufferedImage spectrumImage = chartPanel.createImage();

        int fullImageWidth = spectrumImage.getWidth() + sidebarWidth;

        BufferedImage imageToWrite = new BufferedImage(fullImageWidth,
                spectrumImage.getHeight(), BufferedImage.TYPE_INT_RGB);

        Graphics2D g = imageToWrite.createGraphics();
        g.drawImage(spectrumImage, sidebarWidth, 0, null);

        //write in sidebar
        int lineHeight = 20;
        int lineNum = 1;
        int indent = 5;
        g.setPaint(Color.RED);
        g.drawString(peptide, indent, lineNum++ * lineHeight);
        g.drawString("Charge=" + charge, indent, lineNum++ * lineHeight);
        g.drawString("Light mass=" + lightMass, indent, lineNum++ * lineHeight);
        g.drawString("Light m/z=" + lightMz, indent, lineNum++ * lineHeight);
        g.drawString("Heavy m/z=" + heavyMz, indent, lineNum++ * lineHeight);
        g.drawString("Light int=" + lightIntensity, indent, lineNum++ * lineHeight);
        g.drawString("Heavy int=" + heavyIntensity, indent, lineNum++ * lineHeight);
        g.drawString("Ratio=" + ratio, indent, lineNum++ * lineHeight);
        g.drawString("MinscanLt=" + lightMinQuantScan, indent, lineNum++ * lineHeight);
        g.drawString("MaxscanLt=" + lightMaxQuantScan, indent, lineNum++ * lineHeight);
        g.drawString("MinScanHv=" + heavyMinQuantScan, indent, lineNum++ * lineHeight);
        g.drawString("MaxScanHv=" + heavyMaxQuantScan, indent, lineNum++ * lineHeight);
        g.drawString("ID scan=" + idScan, indent, lineNum++ * lineHeight);
        g.drawString("IDscan level=" + idScanLevel, indent, lineNum++ * lineHeight);

        g.dispose();

        ImageIO.write(imageToWrite,"png",outFile);
    }


    /**
     * This class is for creating the header of HTML and TSV files, and parsing TSV file headers
     */
    protected class IOManager
    {
        public final String[] dataColumnNames = new String[]
                {
                        "Protein",
                        "Peptide",
                        "Fraction",
                        "Charge",
                        "Scan",
                        "Spectrum",
                        "Scans",
                        "3D",
                        "Ratio",
                        "Light",
                        "Heavy",
                        "LightFirstScan",
                        "LightLastScan",
                        "HeavyFirstScan",
                        "HeavyLastScan",
                        "AllEventScans",
                };

        protected boolean[] columnsAreFiles = new boolean[]
                {
                        false, false,false,false,false,true,true,true,false,false,false,false,false,false,false,false,
                };

        public IOManager()
        {

        }

        public void writeHeader()
        {

            if (outHtmlPW != null)
            {
                outHtmlPW.println(HtmlGenerator.createDocumentBeginning("Quantitative Events"));
                outHtmlPW.print("<table border=\"1\"><tr>");
                for (String columnHeader : dataColumnNames)
                {
                    if (shouldShowColumn(columnHeader))
                        outHtmlPW.print("<th>" + columnHeader + "</th>");
                }
                outHtmlPW.println("</tr>");
                outHtmlPW.flush();
            }
            if (outTsvPW != null)
            {
                boolean firstColumnShown = true;
                for (int i=0; i < dataColumnNames.length; i++)
                {
                    String columnHeader = dataColumnNames[i];
                    if (shouldShowColumn(columnHeader))
                    {
                        if (!firstColumnShown)
                            outTsvPW.print("\t");
                        outTsvPW.print(columnHeader);
                        firstColumnShown = false;
                    }
                }
                outTsvPW.println();
                outTsvPW.flush();
            }
        }

        protected boolean shouldShowColumn(String columnName)
        {
            if (!showProteinColumn && "Protein".equals(columnName))
                return false;
            if (!show3DPlots && "3D".equals(columnName))
                return false;
            return true;
        }

        public void writeFooterAndClose()
        {
            if (outHtmlPW != null)
            {
                outHtmlPW.println("</table>");
                outHtmlPW.flush();
                outHtmlPW.close();
            }
            if (outTsvPW != null)
                outTsvPW.close();
        }

    }

    /**
     * Holds all the information related to a quantitative event for display
     */
    protected static class QuantEventInfo
    {

        protected String protein;
        protected String peptide;
        protected String fraction;
        protected int charge;
        protected int scan;
        protected File spectrumFile;
        protected File scansFile;
        protected File file3D;
        protected float ratio;
        protected float lightIntensity;
        protected float heavyIntensity;
        protected int firstLightQuantScan;
        protected int lastLightQuantScan;
        protected int firstHeavyQuantScan;
        protected int lastHeavyQuantScan;
        protected String allScans;

        public QuantEventInfo(String protein, String peptide, String fraction, int charge, int scan,
                              File spectrumFile, File scansFile, File file3D,
                              float ratio, float lightIntensity, float heavyIntensity,
                              int firstLightQuantScan, int lastLightQuantScan,
                              int firstHeavyQuantScan, int lastHeavyQuantScan,
                              String allScans)
        {
           this.protein = protein;
            this.peptide = peptide;
            this.fraction = fraction;
            this.charge = charge;
            this.scan=scan;
            this.spectrumFile = spectrumFile;
            this.scansFile = scansFile;
            this.file3D = file3D;
            this.ratio = ratio;
            this.lightIntensity = lightIntensity;
            this.heavyIntensity = heavyIntensity;
            this.firstLightQuantScan = firstLightQuantScan;
            this.lastLightQuantScan = lastLightQuantScan;
            this.firstHeavyQuantScan = firstHeavyQuantScan;
            this.lastHeavyQuantScan = lastHeavyQuantScan;
            this.allScans = allScans;
        }

        public String createOutputRowHtml(String outChartsRelativeDirPath, boolean showProteinColumn,
                                      boolean show3DColumn)
        {
            return createOutputRow(outChartsRelativeDirPath, true, showProteinColumn, show3DColumn);
        }

        public String createOutputRowTsv(boolean showProteinColumn,
                                      boolean show3DColumn)
        {
            return createOutputRow(null, false, showProteinColumn, show3DColumn);
        }

        protected String createOutputRow(String outChartsRelativeDirPath, boolean isHtml, boolean showProteinColumn,
                                      boolean show3DColumn)
        {
            String spectrumFileString = spectrumFile.getAbsolutePath();
            String scansFileString = scansFile.getAbsolutePath();
            String fileString3D = null;
            if (show3DColumn)
                fileString3D = file3D.getAbsolutePath();

            if (isHtml)
            {
                spectrumFileString = HtmlGenerator.createLink(outChartsRelativeDirPath + spectrumFile.getName(),"Spectrum");
                scansFileString = HtmlGenerator.createLink(outChartsRelativeDirPath + scansFile.getName(), "Scans");
                if (show3DColumn)
                    fileString3D = HtmlGenerator.createLink(outChartsRelativeDirPath + file3D.getName(), "3D");
            }
            
            List<String> stringValuesForRow = new ArrayList<String>();
            if (showProteinColumn)
                stringValuesForRow.add(protein);
            stringValuesForRow.add(    peptide);
            stringValuesForRow.add(fraction);
            stringValuesForRow.add( "" + charge);
            stringValuesForRow.add( "" + scan);
            stringValuesForRow.add( spectrumFileString);
            stringValuesForRow.add(scansFileString);
            if (show3DColumn)
                stringValuesForRow.add(fileString3D);
            stringValuesForRow.add( "" + ratio);
            stringValuesForRow.add("" + lightIntensity);
            stringValuesForRow.add("" + heavyIntensity);
            stringValuesForRow.add("" + firstLightQuantScan);
            stringValuesForRow.add("" + lastLightQuantScan);
            stringValuesForRow.add( "" + firstHeavyQuantScan);
            stringValuesForRow.add("" + lastHeavyQuantScan);
            stringValuesForRow.add("" + allScans);

            String result = null;
            if (isHtml)
                result = HtmlGenerator.createTableRow(stringValuesForRow);
            else
            {
                StringBuffer resultBuf = new StringBuffer();
                boolean firstCol = true;
                for (String value : stringValuesForRow)
                {
                    if (!firstCol)
                        resultBuf.append("\t");
                    resultBuf.append(value);
                    firstCol = false;
                }
                result = resultBuf.toString();
            }
            return result;
        }


        public String getProtein()
        {
            return protein;
        }

        public void setProtein(String protein)
        {
            this.protein = protein;
        }

        public String getPeptide()
        {
            return peptide;
        }

        public void setPeptide(String peptide)
        {
            this.peptide = peptide;
        }

        public String getFraction()
        {
            return fraction;
        }

        public void setFraction(String fraction)
        {
            this.fraction = fraction;
        }

        public int getCharge()
        {
            return charge;
        }

        public void setCharge(int charge)
        {
            this.charge = charge;
        }

        public int getScan()
        {
            return scan;
        }

        public void setScan(int scan)
        {
            this.scan = scan;
        }

        public File getSpectrumFile()
        {
            return spectrumFile;
        }

        public void setSpectrumFile(File spectrumFile)
        {
            this.spectrumFile = spectrumFile;
        }

        public File getScansFile()
        {
            return scansFile;
        }

        public void setScansFile(File scansFile)
        {
            this.scansFile = scansFile;
        }

        public File getFile3D()
        {
            return file3D;
        }

        public void setFile3D(File file3D)
        {
            this.file3D = file3D;
        }

        public float getRatio()
        {
            return ratio;
        }

        public void setRatio(float ratio)
        {
            this.ratio = ratio;
        }

        public float getLightIntensity()
        {
            return lightIntensity;
        }

        public void setLightIntensity(float lightIntensity)
        {
            this.lightIntensity = lightIntensity;
        }

        public float getHeavyIntensity()
        {
            return heavyIntensity;
        }

        public void setHeavyIntensity(float heavyIntensity)
        {
            this.heavyIntensity = heavyIntensity;
        }

        public int getFirstLightQuantScan()
        {
            return firstLightQuantScan;
        }

        public void setFirstLightQuantScan(int firstLightQuantScan)
        {
            this.firstLightQuantScan = firstLightQuantScan;
        }

        public int getLastLightQuantScan()
        {
            return lastLightQuantScan;
        }

        public void setLastLightQuantScan(int lastLightQuantScan)
        {
            this.lastLightQuantScan = lastLightQuantScan;
        }

        public int getFirstHeavyQuantScan()
        {
            return firstHeavyQuantScan;
        }

        public void setFirstHeavyQuantScan(int firstHeavyQuantScan)
        {
            this.firstHeavyQuantScan = firstHeavyQuantScan;
        }

        public int getLastHeavyQuantScan()
        {
            return lastHeavyQuantScan;
        }

        public void setLastHeavyQuantScan(int lastHeavyQuantScan)
        {
            this.lastHeavyQuantScan = lastHeavyQuantScan;
        }

        public String getAllScans()
        {
            return allScans;
        }

        public void setAllScans(String allScans)
        {
            this.allScans = allScans;
        }
    }

    public int getResolution()
    {
        return resolution;
    }

    public void setResolution(int resolution)
    {
        this.resolution = resolution;
    }

    public int getNumHeavyPeaksToPlot()
    {
        return numHeavyPeaksToPlot;
    }

    public void setNumHeavyPeaksToPlot(int numHeavyPeaksToPlot)
    {
        this.numHeavyPeaksToPlot = numHeavyPeaksToPlot;
    }

    public File getOutDir()
    {
        return outDir;
    }

    public void setOutDir(File outDir)
    {
        this.outDir = outDir;
    }

    public int getScanImageHeight()
    {
        return scanImageHeight;
    }

    public void setScanImageHeight(int scanImageHeight)
    {
        this.scanImageHeight = scanImageHeight;
    }

    public int getMaxScansImageHeight()
    {
        return maxScansImageHeight;
    }

    public void setMaxScansImageHeight(int maxScansImageHeight)
    {
        this.maxScansImageHeight = maxScansImageHeight;
    }

    public int getSpectrumImageHeight()
    {
        return spectrumImageHeight;
    }

    public void setSpectrumImageHeight(int spectrumImageHeight)
    {
        this.spectrumImageHeight = spectrumImageHeight;
    }

    public int getImageWidth()
    {
        return imageWidth;
    }

    public void setImageWidth(int imageWidth)
    {
        this.imageWidth = imageWidth;
    }

    public File getMzXmlDir()
    {
        return mzXmlDir;
    }

    public void setMzXmlDir(File mzXmlDir)
    {
        this.mzXmlDir = mzXmlDir;
    }

    public float getMaxCombineFeatureRatioDiff()
    {
        return maxCombineFeatureRatioDiff;
    }

    public void setMaxCombineFeatureRatioDiff(float maxCombineFeatureRatioDiff)
    {
        this.maxCombineFeatureRatioDiff = maxCombineFeatureRatioDiff;
    }

    public boolean isWriteHTMLAndText()
    {
        return writeHTMLAndText;
    }

    public void setWriteHTMLAndText(boolean writeHTMLAndText)
    {
        this.writeHTMLAndText = writeHTMLAndText;
    }

    public float getMinPeptideProphet()
    {
        return minPeptideProphet;
    }

    public void setMinPeptideProphet(float minPeptideProphet)
    {
        this.minPeptideProphet = minPeptideProphet;
    }

    public File getMzXmlFile()
    {
        return mzXmlFile;
    }

    public void setMzXmlFile(File mzXmlFile)
    {
        this.mzXmlFile = mzXmlFile;
    }

    public int getNumPaddingScans()
    {
        return numPaddingScans;
    }

    public void setNumPaddingScans(int numPaddingScans)
    {
        this.numPaddingScans = numPaddingScans;
    }

    public float getMzPadding()
    {
        return mzPadding;
    }

    public void setMzPadding(float mzPadding)
    {
        this.mzPadding = mzPadding;
    }

    public Set<String> getPeptidesFound()
    {
        return peptidesFound;
    }

    public void setPeptidesFound(Set<String> peptidesFound)
    {
        this.peptidesFound = peptidesFound;
    }

    public int getSidebarWidth()
    {
        return sidebarWidth;
    }

    public void setSidebarWidth(int sidebarWidth)
    {
        this.sidebarWidth = sidebarWidth;
    }

    public Map<String, Map<String, Map<String, Map<Integer, List<Pair<File, File>>>>>> getProteinPeptideFractionChargeFilesMap()
    {
        return proteinPeptideFractionChargeFilesMap;
    }

    public void setProteinPeptideFractionChargeFilesMap(Map<String, Map<String, Map<String, Map<Integer, List<Pair<File, File>>>>>> proteinPeptideFractionChargeFilesMap)
    {
        this.proteinPeptideFractionChargeFilesMap = proteinPeptideFractionChargeFilesMap;
    }

    public PrintWriter getOutHtmlPW()
    {
        return outHtmlPW;
    }

    public void setOutHtmlPW(PrintWriter outHtmlPW)
    {
        this.outHtmlPW = outHtmlPW;
    }

    public PrintWriter getOutTsvPW()
    {
        return outTsvPW;
    }

    public void setOutTsvPW(PrintWriter outTsvPW)
    {
        this.outTsvPW = outTsvPW;
    }

    public boolean isShow3DPlots()
    {
        return show3DPlots;
    }

    public void setShow3DPlots(boolean show3DPlots)
    {
        this.show3DPlots = show3DPlots;
    }

    public int getRotationAngle3D()
    {
        return rotationAngle3D;
    }

    public void setRotationAngle3D(int rotationAngle3D)
    {
        this.rotationAngle3D = rotationAngle3D;
    }

    public int getTiltAngle3D()
    {
        return tiltAngle3D;
    }

    public void setTiltAngle3D(int tiltAngle3D)
    {
        this.tiltAngle3D = tiltAngle3D;
    }

    public int getImageHeight3D()
    {
        return imageHeight3D;
    }

    public void setImageHeight3D(int imageHeight3D)
    {
        this.imageHeight3D = imageHeight3D;
    }

    public int getImageWidth3D()
    {
        return imageWidth3D;
    }

    public void setImageWidth3D(int imageWidth3D)
    {
        this.imageWidth3D = imageWidth3D;
    }

    public boolean isShow3DAxes()
    {
        return show3DAxes;
    }

    public void setShow3DAxes(boolean show3DAxes)
    {
        this.show3DAxes = show3DAxes;
    }

    public int getScan()
    {
        return scan;
    }

    public void setScan(int scan)
    {
        this.scan = scan;
    }

    public IOManager getIoManager()
    {
        return ioManager;
    }

    public void setIoManager(IOManager ioManager)
    {
        this.ioManager = ioManager;
    }

    public Set<String> getPeptidesToExamine()
    {
        return peptidesToExamine;
    }

    public void setPeptidesToExamine(Set<String> peptidesToExamine)
    {
        this.peptidesToExamine = peptidesToExamine;
    }

    public Set<String> getProteinsToExamine()
    {
        return proteinsToExamine;
    }

    public void setProteinsToExamine(Set<String> proteinsToExamine)
    {
        this.proteinsToExamine = proteinsToExamine;
    }

    public Set<String> getFractionsToExamine()
    {
        return fractionsToExamine;
    }

    public void setFractionsToExamine(Set<String> fractionsToExamine)
    {
        this.fractionsToExamine = fractionsToExamine;
    }

    public Iterator<FeatureSet> getFeatureSetIterator()
    {
        return featureSetIterator;
    }

    public void setFeatureSetIterator(Iterator<FeatureSet> featureSetIterator)
    {
        this.featureSetIterator = featureSetIterator;
    }
}