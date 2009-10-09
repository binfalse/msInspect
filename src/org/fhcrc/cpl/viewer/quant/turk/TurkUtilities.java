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
package org.fhcrc.cpl.viewer.quant.turk;

import org.fhcrc.cpl.toolbox.commandline.arguments.ArgumentValidationException;
import org.fhcrc.cpl.toolbox.commandline.arguments.CommandLineArgumentDefinition;
import org.fhcrc.cpl.toolbox.commandline.arguments.FileToReadArgumentDefinition;
import org.fhcrc.cpl.toolbox.commandline.arguments.BooleanArgumentDefinition;
import org.fhcrc.cpl.toolbox.commandline.CommandLineModuleExecutionException;
import org.fhcrc.cpl.toolbox.commandline.CommandLineModule;
import org.fhcrc.cpl.toolbox.filehandler.TempFileManager;
import org.fhcrc.cpl.toolbox.filehandler.TabLoader;
import org.fhcrc.cpl.toolbox.ApplicationContext;
import org.fhcrc.cpl.toolbox.datastructure.Pair;
import org.fhcrc.cpl.toolbox.statistics.BasicStatistics;
import org.fhcrc.cpl.toolbox.gui.chart.PanelWithHistogram;
import org.fhcrc.cpl.viewer.commandline.modules.BaseViewerCommandLineModuleImpl;
import org.fhcrc.cpl.viewer.quant.QuantEvent;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.*;
import java.util.List;


/**
 * Utilities for working with the Mechanical Turk
 */
public class TurkUtilities
{
    protected static Logger _log = Logger.getLogger(TurkUtilities.class);

    /**
     * Return a list of pairs for each HIT.  The pairs are:
     * first=did the workers think this was good?
     * second=how many workers agreed?
     * todo: figure out what to do for even numbers of workers, if necessary
     * @param responsesPerHit
     * @return
     */
    public static List<Pair<Boolean, Integer>> summarizeWorkerConsensus(List<List<HITResponse>> responsesPerHit)
    {
        List<Pair<Boolean, Integer>> result = new ArrayList<Pair<Boolean, Integer>>();
        for (int i=0; i<responsesPerHit.size(); i++)
        {

            List<TurkUtilities.HITResponse> responses = responsesPerHit.get(i);
            int sumTrue = 0;
            int sumFalse = 0;
            for (TurkUtilities.HITResponse response : responses)
            {
                if (response.isResponseGood())
                    sumTrue++;
                else sumFalse++;
            }
            result.add(new Pair<Boolean, Integer>(sumTrue > sumFalse, Math.max(sumTrue, sumFalse)));
        }
        return result;
    }

    /**
     * Group HIT responses by HIT.  Assumes HITs numbered consecutively starting with 0
     * @param hitResponses
     * @return
     */
    public static List<List<HITResponse>> groupResponsesByHIT(List<HITResponse> hitResponses)
    {
        Set<Integer> hitIds = new HashSet<Integer>();
        for (TurkUtilities.HITResponse hitResponse : hitResponses)
        {
            hitIds.add(hitResponse.getHitId());
        }
        List<List<TurkUtilities.HITResponse>> responsesPerHit = new ArrayList<List<TurkUtilities.HITResponse>>();
        for (Integer hit : hitIds)
            responsesPerHit.add(new ArrayList<TurkUtilities.HITResponse>());
        for (TurkUtilities.HITResponse hitResponse : hitResponses)
        {
            responsesPerHit.get(hitResponse.getHitId()).add(hitResponse);
        }
        return responsesPerHit;
    }

    /**
     * Load HIT responses from a .csv file
     */
    public static List<HITResponse> loadHITResponses(File turkResultFile) throws IOException
    {
        //convert csv to tsv, so we can use TabLoader.  While I'm at it, remove all those lousy quote marks
        //todo: use some real csv handler
        BufferedReader br =  new BufferedReader(new FileReader(turkResultFile));
        File tsvFile = TempFileManager.createTempFile("turktemp.tsv", "DUMMY_TURKHIT");
        PrintWriter outPW = null;
        String line = null;
        outPW = new PrintWriter(tsvFile);
        //get rid of extraneous commas and turn commas into tabs
        while ((line = br.readLine()) != null)
        {
            //get rid of commas between tags
            //todo: this assumes tags begin with science.  If not, commas will be left in the tags -> failure
            line = line.replaceAll("science,[^\"]*\"","tags\"");

            line = line.replaceAll("\"","");
            line = line.replaceAll(",","\t");
            outPW.println(line);
            outPW.flush();
        }
        outPW.close();

        Map<String, Object>[] rowMaps = null;
        try
        {
            TabLoader tl = new TabLoader(tsvFile);
            rowMaps = (Map<String, Object>[]) tl.load();
        }
        catch (IOException e)
        {
            throw e;
        }
        finally
        {
            TempFileManager.deleteTempFiles("DUMMY_TURKHIT");
        }

        List<HITResponse> hitResponses = new ArrayList<HITResponse>();

        for (Map<String, Object> rowMap : rowMaps)
        {
            int id = (Integer) rowMap.get("Input.id");
            String worker = (String) rowMap.get("WorkerId");
            boolean workerResponse = rowMap.get("Answer.assessment").equals("Good");
            boolean heuristicResponse = rowMap.get("Input.evalstatus").equals("OK");
            HITResponse hitResponse = new HITResponse(id, worker, workerResponse, heuristicResponse);
            hitResponses.add(hitResponse);
        }

        return hitResponses;
    }


    public static class HITResponse
    {
        protected String turkId;
        protected int hitId;
        protected boolean responseGood;
        protected boolean heuristicGood;

        public HITResponse(int hitId, String turkId, boolean turkGood, boolean heuristicGood)
        {
            this.hitId = hitId;
            this.turkId = turkId;
            this.responseGood = turkGood;
            this.heuristicGood = heuristicGood;
        }

        public String getTurkId()
        {
            return turkId;
        }

        public void setTurkId(String turkId)
        {
            this.turkId = turkId;
        }

        public int getHitId()
        {
            return hitId;
        }

        public void setHitId(int hitId)
        {
            this.hitId = hitId;
        }

        public boolean isResponseGood()
        {
            return responseGood;
        }

        public void setResponseGood(boolean responseGood)
        {
            this.responseGood = responseGood;
        }

        public boolean isHeuristicGood()
        {
            return heuristicGood;
        }

        public void setHeuristicGood(boolean heuristicGood)
        {
            this.heuristicGood = heuristicGood;
        }
    }

}
