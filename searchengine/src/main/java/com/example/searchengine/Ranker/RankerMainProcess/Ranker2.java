package com.example.searchengine.Ranker.RankerMainProcess;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

public class Ranker2 {

    long[] FreqSearchTerms;
    Map<String, Map<Integer, int[]>> index;
    long[] DocTerms = new long[6000];
    long[][] DocTermsFreqs;
    long numDocs = 6000;
    long numTerms;
    double[] RelevanceScore;
    double[] pageRankScores;
    double dampingFactor = 0.85;
    int maxIterations = 100;
    double[][] adjacencyMatrix;
    long[] OutDegree;
    double[] finalRankScores;
    int[] finalDocs;

    public Ranker2(Map<String, Map<Integer, int[]>> index, long[] DocTerms, double[][] adjacencyMatrix) {
        this.index = index;
        this.DocTerms = DocTerms;
        this.numDocs = DocTerms.length;
        this.adjacencyMatrix = adjacencyMatrix;
        this.pageRankScores = new double[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            pageRankScores[i] = 1.0 / numDocs;
        }
        this.OutDegree = new long[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            OutDegree[i] = getOutDegree(adjacencyMatrix, i);
        }
    }

    public void calculateRelevanceScore(String[] searchTerms) {
        RelevanceScore = new double[(int) numDocs];
        this.FreqSearchTerms = new long[searchTerms.length];
        this.DocTermsFreqs = new long[DocTerms.length][searchTerms.length];
        this.numTerms = searchTerms.length;
        for (int i = 0; i < numDocs; i++) {
            for (int j = 0; j < numTerms; j++) {
                Map<Integer, int[]> termFreqs = index.get(searchTerms[j]);
                long freq = 0;
                if (termFreqs != null) {
                    int[] positions = termFreqs.get(i);
                    if (positions != null) {
                        // Assign weights based on positions
                       freq+=positions[0]*3+positions[1]*2+positions[2]*1; 
                    }
                }
                DocTermsFreqs[i][j] = freq;
                if (freq > 0) {
                    FreqSearchTerms[j]++;
                }
            }
        }
        for (int i = 0; i < numDocs; i++) {
            RelevanceScore[i] = 0;
            for (int j = 0; j < numTerms; j++) {
                if (DocTermsFreqs[i][j] > 0) {
                    RelevanceScore[i] += (((double) DocTermsFreqs[i][j] / (DocTerms[i] == 0 ? 1 : (double) DocTerms[i]))
                            * (Math.log((double) numDocs / (FreqSearchTerms[j] == 0 ? 1 : (double) FreqSearchTerms[j]))));
                }
            }
        }
    }

    public void calculatePageRank() {
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            double[] newPageRankScores = new double[(int) numDocs];
            for (int i = 0; i < numDocs; i++) {
                for (int j = 0; j < numDocs; j++) {
                    if (adjacencyMatrix[j][i] > 0) {
                        newPageRankScores[i] += (dampingFactor * pageRankScores[j]) / OutDegree[j];
                    }
                }
                newPageRankScores[i] += (1 - dampingFactor) / numDocs;
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
        return outDegree == 0 ? 1 : outDegree;
    }

    public double[] getPageRankScores() {
        return pageRankScores;
    }

    public double[] getRelevanceScore() {
        return RelevanceScore;
    }

    public void calculateFinalRank(String[] searchTerms) {
        calculateRelevanceScore(searchTerms);
        calculatePageRank();
        finalRankScores = new double[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            finalRankScores[i] = 0.7 * RelevanceScore[i] + 0.3 * pageRankScores[i];
        }
    }

    public double[] getFinalRankScores() {
        return finalRankScores;
    }

    public void generateFinalDocs(String[] searchTerms) {
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

    public int[] getFinalDocs(String[] searchTerms) {
        generateFinalDocs(searchTerms);
        return finalDocs;
    }

    static class Pair {
        int value;
        int index;

        Pair(int value, int index) {
            this.value = value;
            this.index = index;
        }
    }
}
