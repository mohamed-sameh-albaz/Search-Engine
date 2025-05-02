package com.example.searchengine.Ranker.RankerMainProcess;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
public class Ranker1 {

   
    long[] FreqSearchTerms;
    Map<String, Map<Integer, Integer>> index;
    long[] DocTerms= new long[6000]; // Assuming a maximum of 6000 documents
    long [][] DocTermsFreqs;
    long numDocs=6000;
    long numTerms;
    double [] RelevanceScore;
    //==========================================================================
    double[] pageRankScores;
    double dampingFactor = 0.85; // Damping factor for PageRank algorithm
    int maxIterations = 100; 
    double [][] adjacencyMatrix;
    long [] OutDegree;
    //==========================================================================

   double [] finalRankScores;
   int [] finalDocs;
   //===========================================================================
    public Ranker1(Map<String, Map<Integer, Integer>> index, long [] DocTerms, double [][] adjacencyMatrix) {
        this.index = index;
        this.DocTerms = DocTerms;
        this.numDocs = DocTerms.length;
        this.adjacencyMatrix = adjacencyMatrix;

        //=========================================================================
        //calc doc terms

        // for (Map<Integer, Integer> docs : index.values()) {
        //     for (Map.Entry<Integer, Integer> docEntry : docs.entrySet()) {
        //         int docId = docEntry.getKey();
        //         int freq = docEntry.getValue();
        //         DocTerms[docId] += freq; // Increment the term frequency for the document
        //     }
        // }
        //=========================================================================
        this.pageRankScores = new double[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            pageRankScores[i] = 1.0 / numDocs; // Initialize scores uniformly
        }
        this.OutDegree = new long[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            OutDegree[i] = getOutDegree(adjacencyMatrix, i); // Calculate out-degree for each node
        }
        //=========================================================================
    }

    public void calculateRelevanceScore(String [] searchTerms) {
        RelevanceScore = new double[(int) numDocs];
        this.FreqSearchTerms = new long[searchTerms.length];
        this.DocTermsFreqs = new long[DocTerms.length][searchTerms.length];
        this.numTerms = searchTerms.length;
        for (int i=0;i<numDocs;i++){
            for (int j=0;j<numTerms;j++){
                Map<Integer, Integer> termFreqs = index.get(searchTerms[j]);
                if (termFreqs == null) {
                    DocTermsFreqs[i][j] = 0;
                } else {
                    DocTermsFreqs[i][j] = termFreqs.getOrDefault(i, 0);
                }
                if (DocTermsFreqs[i][j]>0)
                  {
                    FreqSearchTerms[j] ++;
                  }  
            }
        }
        for (int i=0;i<numDocs;i++){
            RelevanceScore[i] = 0;
            for (int j=0;j<numTerms;j++){
                if (DocTermsFreqs[i][j]>0){
                    RelevanceScore[i] += (((double)DocTermsFreqs[i][j]/(DocTerms[i]==0?1:(double)DocTerms[i])) * (Math.log((double)numDocs/(FreqSearchTerms[j]==0 ? 1:(double)FreqSearchTerms[j] ))));
                }
            }
        }
    }

    //===========================================================================
    public void calculatePageRank() {
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            double[] newPageRankScores = new double[(int) numDocs];
            for (int i = 0; i < numDocs; i++) {
                for (int j = 0; j < numDocs; j++) {
                    if (adjacencyMatrix[j][i] > 0) { // If there is a link from j to i
                        newPageRankScores[i] += (dampingFactor * pageRankScores[j]) / OutDegree[j];
                    }
                }
                newPageRankScores[i] += (1 - dampingFactor) / numDocs; // Add the random jump factor
            }
            pageRankScores = newPageRankScores;
        }
    }

    private int getOutDegree(double[][] adjacencyMatrix, int node) {
        int outDegree = 0;
        for (int j = 0; j < numDocs; j++) {
            if (adjacencyMatrix[node][j] > 0) {
                outDegree++;
            }
        }
        return outDegree == 0 ? 1 : outDegree; // Avoid division by zero
    }

    public double[] getPageRankScores() {
        return pageRankScores;
    }

    //===========================================================================

    public double[] getRelevanceScore() {
        return RelevanceScore;
    }

   public void calculateFinalRank(String [] searchTerms) {

        calculateRelevanceScore(searchTerms);
        calculatePageRank(); 
        finalRankScores = new double[(int) numDocs];
        for (int i=0;i<numDocs;i++){
            finalRankScores[i] = 0.7*RelevanceScore[i] + 0.3*pageRankScores[i];
        }
    }

    public double[] getFinalRankScores() {
        return finalRankScores;
   }

   public void generateFinalDocs(String [] searchTerms) {

    calculateFinalRank(searchTerms);
    Pair[] pairs = new Pair[(int) numDocs];  
    for (int i = 0; i < numDocs; i++) {
        pairs[i] = new Pair((int) finalRankScores[i], i);
    }
     Arrays.sort(pairs, new Comparator<Pair>() {
            @Override
            public int compare(Pair p1, Pair p2) {
                return Integer.compare(p2.value, p1.value); 
            }
        });
        finalDocs = new int[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            finalDocs[i] = pairs[i].index;
        }
   }

    public int[] getFinalDocs(String [] searchTerms) {

        generateFinalDocs(searchTerms);
        return finalDocs;
    }
    
     //==========================================================================
 

   
   static class Pair {
    int value;
    int index;

    Pair(int value, int index) {
        this.value = value;
        this.index = index;
    }
}
}

