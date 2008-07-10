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
package org.fhcrc.cpl.viewer.feature;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.GregorianCalendar;
import java.math.BigInteger;

import org.apache.log4j.Logger;

import net.systemsbiology.regisWeb.pepXML.*;
import org.fhcrc.cpl.viewer.feature.extraInfo.MS2ExtraInfoDef;
import org.fhcrc.cpl.viewer.feature.extraInfo.IsotopicLabelExtraInfoDef;
import org.fhcrc.cpl.toolbox.proteomics.MS2Modification;
import org.fhcrc.cpl.toolbox.proteomics.ModifiedAminoAcid;
import org.fhcrc.cpl.toolbox.proteomics.filehandler.Q3Handler;
import org.fhcrc.cpl.toolbox.proteomics.filehandler.XPressHandler;
import org.fhcrc.cpl.toolbox.proteomics.filehandler.BasePepXmlWriter;
import org.fhcrc.cpl.toolbox.proteomics.filehandler.RelativeQuantAnalysisResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Attr;

/**
 * A restrictive wrapper for writing PepXml files, using a Feature array to populate the spectrum_queries
 */
public class FeaturePepXmlWriter extends BasePepXmlWriter
{
    static Logger _log = Logger.getLogger(FeaturePepXmlWriter.class);

    //all features and modifications to be written
    protected Feature[] _features = null;

    protected boolean writeIntensitiesAsXpressResults = false;

    protected int firstSpectrumQueryIndex = 1;

    public final int RATIO_MODE_XPRESS = 0;
    public final int RATIO_MODE_Q3 = 1;

    //we have to write out ratios, whose provenance we don't know, as either Q3 or XPress.
    //This variable determines which
    protected int ratioMode = RATIO_MODE_Q3;

    protected AnalyzeICAT.IsotopicLabel _isotopicLabel = null;


    /**
     * Create doc structure, populate features and modifications
     * @param features
     * @param modifications
     */
    public FeaturePepXmlWriter(Feature[] features, MS2Modification[] modifications)
    {
        super(modifications);
        setFeatures(features);
    }

    public FeaturePepXmlWriter(FeatureSet featureSet)
    {
        super();
        setModifications(MS2ExtraInfoDef.getFeatureSetModifications(featureSet));
        setSearchDatabase(MS2ExtraInfoDef.getFeatureSetSearchDatabasePath(featureSet));

        String baseName = MS2ExtraInfoDef.getFeatureSetBaseName(featureSet);
        if (featureSet != null)
            setBaseName(baseName);

        int maxCleavages =
                MS2ExtraInfoDef.getFeatureSetSearchConstraintMaxIntCleavages(featureSet);
        int minTermini =
                MS2ExtraInfoDef.getFeatureSetSearchConstraintMinTermini(featureSet);

        setSearchConstraints(maxCleavages, minTermini);
                
        setFeatures(featureSet.getFeatures());
    }

    /**
     * setter for features
     * @param features
     */
    public void setFeatures(Feature[] features)
    {
        _features = features;
    }


    public boolean willWriteIntensitiesAsXpressResults()
    {
        return writeIntensitiesAsXpressResults;
    }

    public void setWriteIntensitiesAsXpressResults(boolean writeIntensitiesAsXpressResults)
    {
        this.writeIntensitiesAsXpressResults = writeIntensitiesAsXpressResults;
    }


    /**
     * Write out all features immediately
     * @param pw
     */
    protected void writeSpectrumQueries(PrintWriter pw)
    {
        if (_features == null)
            return;
        for (int i=0; i<_features.length; i++)
        {
            //only write features with peptide identifications
            if (MS2ExtraInfoDef.getFirstPeptide(_features[i]) != null)
                writeFeature(i, pw);
        }
    }

    /**
     * Add a SearchResult representing the passed-in feature to the first run of the file.
     * Write out the XML for that search result
     */
    protected void writeFeature(int featureIndex, PrintWriter pw)
    {
        Feature feature = _features[featureIndex];

        int indexAttribute = featureIndex + firstSpectrumQueryIndex;
        MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SpectrumQuery spectrumQuery =
                addSpectrumQuery(feature.getScanFirst(),
                                 feature.getScanLast(),
                        feature.getCharge(), featureIndex + firstSpectrumQueryIndex);
        spectrumQuery.setSpectrum(_spectrumBaseString + indexAttribute);
        spectrumQuery.setPrecursorNeutralMass(feature.getMass() + MS2ExtraInfoDef.getDeltaMass(feature));

        //dhmay adding 7/1/08.  retention_time_sec isn't defined in the pepXml spec, but it's used by
        //various folks
        if (feature.getTime() > 0)
        {
            Attr retentionTimeAttr =
                    spectrumQuery.getDomNode().getOwnerDocument().createAttribute("retention_time_sec");
            retentionTimeAttr.setValue("" + feature.getTime());
            spectrumQuery.getDomNode().getAttributes().setNamedItem(retentionTimeAttr);
        }

        MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SpectrumQuery.SearchResult.SearchHit searchHit =
                spectrumQuery.addNewSearchResult().addNewSearchHit();
        searchHit.setPeptide(MS2ExtraInfoDef.getFirstPeptide(feature));
        searchHit.setCalcNeutralPepMass(feature.getMass());
        searchHit.setHitRank(1);
        searchHit.setNumMatchedIons(BigInteger.valueOf(1));
        searchHit.setNumTotProteins(1);
        searchHit.setMassdiff(Float.toString(MS2ExtraInfoDef.getDeltaMass(feature)));
        searchHit.setNumMissedCleavages(BigInteger.valueOf(0));
        searchHit.setIsRejected(BigInteger.valueOf(0));

        //num_tol_term is important to ProteinProphet.  From David Shteynburg:
        //"In general, having NTT information is very important for distinguishing correct from
        // incorrect assignments both on the peptide and on the protein levels and this information
        // should be given whenever available."
        //If numTolTerm is unspecified but prevAA and nextAA are specified, ProteinProphet will
        //calculate numTolTerm, assuming trypsin
        searchHit.setNumTolTerm(BigInteger.valueOf(2));
        Character prevAA = MS2ExtraInfoDef.getPrevAminoAcid(feature);
        Character nextAA = MS2ExtraInfoDef.getNextAminoAcid(feature);

        if (prevAA != null)
            searchHit.setPeptidePrevAa("" + prevAA);
        if (nextAA != null)
            searchHit.setPeptideNextAa("" + nextAA);

        if (MS2ExtraInfoDef.hasNumEnzymaticEnds(feature))
            searchHit.setNumTolTerm(BigInteger.valueOf(MS2ExtraInfoDef.getNumEnzymaticEnds(feature)));



        //Properly, we should actually carry the previous and next AAs forward
        //through the Feature
//Should we do this?   The goal would be to get ProteinProphet to specify
//n_enzymatic_termini, but that doesn't seem to work
//        searchHit.setPeptidePrevAa("K");
//        searchHit.setPeptideNextAa("A");


        List<String> proteins =
                MS2ExtraInfoDef.getProteinList(feature);
        if (proteins != null && proteins.size() > 0)
        {
            searchHit.setProtein(proteins.get(0));
            for (int i=1; i<proteins.size(); i++)
            {
                MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SpectrumQuery.SearchResult.SearchHit.AlternativeProtein altProtein =
                        searchHit.addNewAlternativeProtein();
                altProtein.setProtein(proteins.get(i));
            }
        }

        //Peptide Prophet
        if (MS2ExtraInfoDef.hasPeptideProphet(feature))
        {

            addPeptideProphet(searchHit, 
                    (float) MS2ExtraInfoDef.getPeptideProphet(feature),
                    MS2ExtraInfoDef.getAllNttProb(feature),
                    (float) MS2ExtraInfoDef.getFval(feature));
        }

        //search scores
        Map<String,String> searchScoreMap = MS2ExtraInfoDef.getSearchScores(feature);
        if (searchScoreMap != null)
        {
            for (String searchScoreType : searchScoreMap.keySet())
            {
                addSearchScore(searchHit, searchScoreType, searchScoreMap.get(searchScoreType));
            }
        }

        //intensity
//        if (writeIntensitiesAsXpressResults && feature.getIntensity() > 0)
//        {
//            XPressHandler.XPressResult xpressAnalysisResult = new XPressHandler.XPressResult();
//            MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SpectrumQuery.SearchResult.SearchHit.AnalysisResult xmlBeansXpressAnalysisResult =
//                searchHit.addNewAnalysisResult();
//            xmlBeansXpressAnalysisResult.setAnalysis(xpressAnalysisResult.getAnalysisType());
//
//            Element arElement =
//                    xmlBeansXpressAnalysisResult.getDomNode().getOwnerDocument().createElement("xpressratio_result");
//            arElement.setAttribute("ratio", feature.getIntensity() + ":1");
//            arElement.setAttribute("heavy_area", "1");
//            arElement.setAttribute("light_area",""  + feature.getIntensity());
//            xmlBeansXpressAnalysisResult.getDomNode().appendChild(arElement);
//        }
        //NOTE: we don't distinguish between Q3 and XPress results
        if (IsotopicLabelExtraInfoDef.hasRatio(feature))
        {
            String resultElementTagName = null;
            RelativeQuantAnalysisResult myAnalysisResult = null;
            switch (ratioMode)
            {
                case RATIO_MODE_Q3:
                    myAnalysisResult = new Q3Handler.Q3Result();
                    resultElementTagName = "q3ratio_result";
                    break;
                default:
                    myAnalysisResult = new XPressHandler.XPressResult();
                    resultElementTagName = "xpressratio_result";                     
                    break;
            }
            MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SpectrumQuery.SearchResult.SearchHit.AnalysisResult xmlBeansAnalysisResult =
                    searchHit.addNewAnalysisResult();
            xmlBeansAnalysisResult.setAnalysis(myAnalysisResult.getAnalysisType());

            Element arElement =
                    xmlBeansAnalysisResult.getDomNode().getOwnerDocument().createElement(resultElementTagName);
            arElement.setAttribute("decimal_ratio", "" + IsotopicLabelExtraInfoDef.getRatio(feature));
            arElement.setAttribute("heavy_area", "" + IsotopicLabelExtraInfoDef.getHeavyIntensity(feature));
            arElement.setAttribute("light_area", "" + IsotopicLabelExtraInfoDef.getLightIntensity(feature));
            arElement.setAttribute("light_mass", "" + feature.getMass());

            if (IsotopicLabelExtraInfoDef.hasLabel(feature))
            {
                int labelCount = IsotopicLabelExtraInfoDef.getLabelCount(feature);
                //HACK... sometimes this value doesn't get stored correctly on features
                if (labelCount == 0) labelCount++;
                float heavyMass = IsotopicLabelExtraInfoDef.getLabel(feature).getHeavy();
                float lightMass = IsotopicLabelExtraInfoDef.getLabel(feature).getLight();
                float massDiff =  labelCount * (heavyMass - lightMass);
                arElement.setAttribute("heavy_mass", "" + (feature.getMass() + massDiff));
            }
            else
            {
                float massDiff = 0;
                if (_isotopicLabel != null)
                    massDiff = _isotopicLabel.getHeavy() - _isotopicLabel.getLight();
                arElement.setAttribute("heavy_mass", "" + (feature.getMass() + massDiff));
            }


            arElement.setAttribute("heavy_firstscan", "" + feature.getScanFirst());
            arElement.setAttribute("heavy_lastscan", "" + feature.getScanLast());
            arElement.setAttribute("light_firstscan", "" + feature.getScanFirst());
            arElement.setAttribute("light_lastscan", "" + feature.getScanLast());

            switch (ratioMode)
            {
                case RATIO_MODE_Q3:
                    arElement.setAttribute("q2_light_area", "" + IsotopicLabelExtraInfoDef.getLightIntensity(feature));
                    arElement.setAttribute("q2_heavy_area", "" + IsotopicLabelExtraInfoDef.getHeavyIntensity(feature));                    
                    break;
                default:
                    arElement.setAttribute("heavy2light_ratio", "" + IsotopicLabelExtraInfoDef.getRatio(feature));                    
                    break;
            }

            xmlBeansAnalysisResult.getDomNode().appendChild(arElement);
        }

        List<ModifiedAminoAcid>[] modifiedAminoAcids = MS2ExtraInfoDef.getModifiedAminoAcids(feature);
        addModifiedAminoAcidsToSearchHit(searchHit, modifiedAminoAcids);

        try
        {
            String fragment = _firstRunSummary.getSpectrumQueryArray(0).xmlText(_optionsForPrinting);
//            String[] pieces = fragment.split("<[\\/]*xml-fragment[^>]*>");
//            fragment = pieces[pieces.length-1];
            fragment = fragment.replaceAll("<pep:","<");
            fragment = fragment.replaceAll("</pep:","</");

            fragment = fragment + "\n";

            pw.print(fragment);
            pw.flush();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }


        _xmlBeansRunSummaryArray[0].removeSpectrumQuery(0);
    }

    protected void preWrite()
    {
        super.preWrite();

        boolean hasRatios = false;
        for (Feature feature : _features)
            if (IsotopicLabelExtraInfoDef.hasRatio(feature))
            {
                hasRatios = true;
                if (IsotopicLabelExtraInfoDef.hasLabel(feature))
                    _isotopicLabel = IsotopicLabelExtraInfoDef.getLabel(feature);
                break;
            }
        if (hasRatios)
        {
            MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.AnalysisSummary quantAnalysisSummary =
                    _xmlBeansAnalysis.addNewAnalysisSummary();
            switch (ratioMode)
            {
                case RATIO_MODE_Q3:
                    quantAnalysisSummary.setAnalysis("q3");
                    Element arElement =
                            quantAnalysisSummary.getDomNode().getOwnerDocument().createElement("q3ratio_summary");
                    arElement.setAttribute("version", "1.2");
                    arElement.setAttribute("author","Marc Coram");
                    if (_isotopicLabel != null)
                    {
                        arElement.setAttribute("labeled_residues", "" + _isotopicLabel.getResidue());
                        arElement.setAttribute("massdiff", "" + (_isotopicLabel.getHeavy() - _isotopicLabel.getLight()));
                    }

                    //TODO: fix this HACK
                    arElement.setAttribute("massTol", ".25");
                    
                    quantAnalysisSummary.getDomNode().appendChild(arElement);

                    //HACK
                    MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.AnalysisTimestamp q3Timestamp =
                            _xmlBeansRunSummaryArray[0].addNewAnalysisTimestamp();
                    q3Timestamp.setAnalysis("q3");
                    q3Timestamp.setTime(new GregorianCalendar());
                    q3Timestamp.setId(1);

                    break;
                case RATIO_MODE_XPRESS:
                    quantAnalysisSummary.setAnalysis("xpress");
                    XpressratioSummaryDocument summaryDoc = XpressratioSummaryDocument.Factory.newInstance();
                    XpressratioSummaryDocument.XpressratioSummary xpressRatioSummaryOtherDoc =
                            summaryDoc.addNewXpressratioSummary();
                    if (_isotopicLabel != null)
                    {
                        xpressRatioSummaryOtherDoc.setXpressLight((long) _isotopicLabel.getLight());
                        xpressRatioSummaryOtherDoc.setMassdiff("" + (_isotopicLabel.getHeavy() - _isotopicLabel.getLight()));
                        xpressRatioSummaryOtherDoc.setLabeledResidues("" + _isotopicLabel.getResidue());
                    }

                    Node ratioSummaryNode =
                            quantAnalysisSummary.getDomNode().getOwnerDocument().importNode(xpressRatioSummaryOtherDoc.getDomNode(), true);
                    quantAnalysisSummary.getDomNode().appendChild(ratioSummaryNode);
                    break;
            }

        }

        //Stuff specific to trypsin
        //TODO: expose this, make it pluggable?

        MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SampleEnzyme
                xmlBeansSampleEnzyme = _firstRunSummary.addNewSampleEnzyme();
        xmlBeansSampleEnzyme.setName("trypsin");
        MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SampleEnzyme.Specificity
                xmlBeansSpecificity = xmlBeansSampleEnzyme.addNewSpecificity();
        xmlBeansSpecificity.setCut("KR");
        xmlBeansSpecificity.setNoCut("P");
        xmlBeansSpecificity.setSense(MsmsPipelineAnalysisDocument.MsmsPipelineAnalysis.MsmsRunSummary.SampleEnzyme.Specificity.Sense.Enum.forString("C"));
    }

    /**
     * Write out the full document, with all modifications and features, to a file
     *
     * It's too bad I have to override the superclass version here.  The only reason
     * that's necessary is to strip out the "pep:" stuff, because programs like RefreshParser
     * only _pretend_ to parse XML.  In practice they're looking for actual strings like
     * "/msms_run_summary"
     * @throws IOException
     */
//    public void write(File file) throws IOException
//    {
//        preWrite();
//
//        //add a sentinel node that tells us where to split the document to insert features and modifications,
//        //which, conveniently, is the same place for both
//        Node runSummaryNode = _firstRunSummary.getDomNode();
//        Node featureLocationNode = runSummaryNode.getOwnerDocument().createElement("SENTINEL_FEATURE_LOCATION");
//
//        runSummaryNode.appendChild(featureLocationNode);
//        //create and break up the xml that defines the document structure
//        String documentShell = _xmlBeansPepXmlDoc.xmlText(_optionsForPrinting);
//
//        String[] halves = documentShell.split("<SENTINEL_FEATURE_LOCATION[^\\/]*\\/>");
//        if (halves.length != 2)
//        {
//            _log.error("Failed to create document shell for writing");
//            return;
//        }
//
//        _documentPrefix = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + halves[0];
//        _documentPostscript = halves[1];
//
//        //remove our dummy node
//        runSummaryNode.removeChild(featureLocationNode);
//
//
//        PrintWriter pw = new PrintWriter(file);
//        pw.print(_documentPrefix);
//        writeModifications(pw);
//        writeSpectrumQueries(pw);
//        pw.print(_documentPostscript);
//        pw.flush();
//    }


    public int getFirstSpectrumQueryIndex()
    {
        return firstSpectrumQueryIndex;
    }

    public void setFirstSpectrumQueryIndex(int firstSpectrumQueryIndex)
    {
        this.firstSpectrumQueryIndex = firstSpectrumQueryIndex;
    }


    public int getRatioMode()
    {
        return ratioMode;
    }

    public void setRatioMode(int ratioMode)
    {
        this.ratioMode = ratioMode;
    }
}
