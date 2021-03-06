package nl.umcg.westrah.binarymetaanalyzer.westrah.binarymetaanalyzer.posthoc.ld;

import nl.umcg.westrah.binarymetaanalyzer.BinaryMetaAnalysis;
import nl.umcg.westrah.binarymetaanalyzer.BinaryMetaAnalysisDataset;
import nl.umcg.westrah.binarymetaanalyzer.MetaQTL4MetaTrait;
import umcg.genetica.console.ProgressBar;
import umcg.genetica.containers.Pair;
import umcg.genetica.io.Gpio;
import umcg.genetica.io.bin.BinaryFile;
import umcg.genetica.io.text.TextFile;
import umcg.genetica.io.trityper.util.BaseAnnot;
import umcg.genetica.text.Strings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BinaryFileSorter extends BinaryMetaAnalysis {

    public BinaryFileSorter(String settingsFile, String textToReplace, String replaceTextWith, boolean usetmp) {
        super(settingsFile, textToReplace, replaceTextWith, usetmp);
    }

    public String getFreeMemory() {
        long free = Runtime.getRuntime().freeMemory();
        long max = Runtime.getRuntime().maxMemory();
        long use = max - free;
        return "Usage: " + Gpio.humanizeFileSize(use) + ", free: " + Gpio.humanizeFileSize(free);
    }

    public void run() throws IOException, Exception {
        super.initialize();


        String outdir = settings.getOutput();
        if (usetmp) {
            outdir = tempDir;
        }

        System.out.println("Placing output here: " + outdir);
        outdir = Gpio.formatAsDirectory(outdir);
        Gpio.createDir(outdir);


        System.out.println("Permutations: " + settings.getStartPermutations() + " until " + settings.getNrPermutations());

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int cores = settings.getNrThreads();
        if (cores < 1) {
            cores = 1;
        } else if (cores > availableProcessors) {
            cores = availableProcessors;
        }
        if (cores > 11) {
            cores = 11;
        }
        System.out.println("Will try to make use of " + cores + " CPU cores");
        System.out.println();

        // load gene/snp combos

        ArrayList<Pair<MetaQTL4MetaTrait, String>> genesnpcombos = loadgenesnpcombos();


        int nrdatasets = settings.getDatasetlocations().size();
        System.out.println("About to process: " + nrdatasets + " datasets");
        ConcurrentHashMap<String, Pair<String, String>> snpalleles = new ConcurrentHashMap<String, Pair<String, String>>();
        for (int d = 0; d < nrdatasets; d++) {
            System.out.println("Booting threadpool with " + cores + " threads");
            ExecutorService executor = Executors.newFixedThreadPool(cores);
            ExecutorCompletionService<BinaryMetaAnalysisDataset> ex = new ExecutorCompletionService<>(executor);
            int submitted = 0;
            String datasetname = settings.getDatasetnames().get(d);
            System.out.println("Procesing dataset: " + datasetname);
            int nrperms = (settings.getNrPermutations() - settings.getStartPermutations()) + 1;
            System.out.println(nrperms);

            AtomicInteger[] ctrs = new AtomicInteger[nrperms];
            for (int perm = settings.getStartPermutations(); perm <= settings.getNrPermutations(); perm++) {
                AtomicInteger c = new AtomicInteger();
                BinaryDatasetInitializerTask t = new BinaryDatasetInitializerTask(perm, d, c);
                ex.submit(t);
                ctrs[submitted] = c;
                submitted++;
            }
            System.out.println(submitted + " jobs submitted, " + nrperms + " permutations");

            int returned = 0;

            BinaryMetaAnalysisDataset[] perms = new BinaryMetaAnalysisDataset[nrperms];
            HashMap<String, Integer> snpmap = new HashMap<String, Integer>();
            int snpctr = 0;

            while (returned < submitted) {
                try {
                    String ctri = "Returned: " + returned + "/" + submitted + "\tNr variants parsed:";

                    System.out.print(ctri + "\r");
                    BinaryMetaAnalysisDataset ds = ex.take().get();
                    if (ds != null) {

                        int perm = ds.getPermutation();
                        perms[perm - settings.getStartPermutations()] = ds;

                        String[] snps = ds.getSNPs();
                        for (String snp : snps) {
                            if (!snpmap.containsKey(snp)) {
                                snpmap.put(snp, snpctr);
                                snpctr++;
                            }
                        }
                        returned++;

                    }

                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            System.out.println();
            System.out.println(perms.length + " datasets returned.");
            System.out.println(getFreeMemory());
            ex = null;

            for (int p = 0; p < perms.length; p++) {
                System.out.println(p + " --> perm: " + perms[p].getPermutation());
            }

            Integer[][] snpindex = new Integer[perms.length][snpmap.size()];
            for (int p = 0; p < perms.length; p++) {
                BinaryMetaAnalysisDataset ds = perms[p];
                String[] snps = ds.getSNPs();
                for (int s = 0; s < snps.length; s++) {
                    int snpid = snpmap.get(snps[s]);
                    snpindex[p][snpid] = s;
                }
            }

            // Dataset loaded. Now iterate combos
            TextFile out = new TextFile(settings.getOutput() + datasetname + "-combos.txt.gz", TextFile.W);
//			TextFile out2 = new TextFile(settings.getOutput() + datasetname + "-failedcombos.txt.gz", TextFile.W);
            SortedBinaryZScoreFile bf = new SortedBinaryZScoreFile(settings.getOutput() + datasetname + "-data.dat", BinaryFile.W); // gzip?
            bf.writeHeader(perms.length);

            ProgressBar pb = new ProgressBar(genesnpcombos.size(), "Converting gene/snp combos dataset: " + (d + 1) + "/" + nrdatasets);


            ArrayList<Future<ReturnObject>> returns = new ArrayList<>();
            int totalwritten = 0;
            int batchsize = 500000;
            int batchctr = 0;
            for (int i = 0; i < genesnpcombos.size(); i++) {
                // submit
                CombineTask t = new CombineTask(snpmap, genesnpcombos, perms, snpalleles, snpindex, i);
                Future<ReturnObject> future = executor.submit(t);
                returns.add(future);

                if (returns.size() % 500000 == 0) {
                    // wait untill stuff has finished, then write
                    boolean alldone = false;
                    ReturnObject[] outarr = new ReturnObject[returns.size()];
                    int ref = batchsize * batchctr;
                    try {
                        while (!alldone) {
                            int nrdone = 0;
                            Thread.sleep(100);
                            for (Future<ReturnObject> f : returns) {
                                if (f.isDone()) {
                                    nrdone++;
                                }
                            }
                            if (nrdone == returns.size()) {
                                alldone = true;
                            }
                        }

                        for (Future<ReturnObject> f : returns) {
                            ReturnObject o = f.get();
                            outarr[(o.i - ref)] = o; // 0 - > 0
                        }

                        for (int j = 0; j < outarr.length; j++) {
                            ReturnObject obj = outarr[j];
                            if (obj == null) {
                                System.out.println("ERROR: obj " + j + " == null");
                            }
                            if (obj.outz != null) {
                                out.writeln(obj.outln);
                                bf.writeZ(obj.outz);
                                totalwritten++;
                            } else {
//								out2.writeln(obj.outln);
                            }
                        }

                        System.out.println(getFreeMemory());

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }
                    returns = new ArrayList<>();
                    pb.set(i);
                    batchctr++;
                }
            }

            // parse last output
            if (returns.size() > 0) {
                boolean alldone = false;
                ReturnObject[] outarr = new ReturnObject[returns.size()];
                int ref = batchsize * batchctr;
                ArrayList<ReturnObject> output = new ArrayList<>();
                try {
                    while (!alldone) {
                        int nrdone = 0;
                        Thread.sleep(100);
                        for (Future<ReturnObject> f : returns) {
                            if (f.isDone()) {
                                nrdone++;
                            }
                        }
                        if (nrdone == returns.size()) {
                            alldone = true;
                        }
                    }

                    for (Future<ReturnObject> f : returns) {
                        ReturnObject o = f.get();
                        outarr[(o.i - ref)] = o; // 0 - > 0
                    }

                    for (int j = 0; j < outarr.length; j++) {
                        ReturnObject obj = outarr[j];
                        if (obj == null) {
                            System.out.println("ERROR: obj " + j + " == null");
                        }
                        if (obj.outz != null) {
                            out.writeln(obj.outln);
                            bf.writeZ(obj.outz);
                            totalwritten++;
                        } else {
//								out2.writeln(obj.outln);
                        }
                    }

                    System.out.println(getFreeMemory());


                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }

            bf.close();
            pb.close();
            out.close();
//			out2.close();
            System.out.println("Closing threads..");
            executor.shutdown();

            System.out.println(totalwritten + " written out of " + genesnpcombos.size());
            System.out.println(getFreeMemory());
        }


        System.out.println("Done");

    }

    class ReturnObject implements Comparable<ReturnObject> {

        public String outln;
        public int i;
        public float[] outz;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ReturnObject that = (ReturnObject) o;
            return i == that.i;
        }

        @Override
        public int hashCode() {
            return i;
        }

        @Override
        public int compareTo(ReturnObject o) {
            if (this.i > o.i) {
                return i;
            } else if (this.i < o.i) {
                return -1;
            } else {
                return 0;
            }
        }
    }

    class CombineTask implements Callable<ReturnObject> {

        HashMap<String, Integer> snpmap;
        ArrayList<Pair<MetaQTL4MetaTrait, String>> combos;
        BinaryMetaAnalysisDataset[] perms;
        ConcurrentHashMap<String, Pair<String, String>> snpalleles;
        Integer[][] snpindex;
        int i;

        public CombineTask(HashMap<String, Integer> snpmap, ArrayList<Pair<MetaQTL4MetaTrait, String>> combos,
                           BinaryMetaAnalysisDataset[] perms, ConcurrentHashMap<String, Pair<String, String>> snpalleles,
                           Integer[][] snpindex, int i) {
            this.snpmap = snpmap;
            this.combos = combos;
            this.perms = perms;
            this.snpalleles = snpalleles;
            this.snpindex = snpindex;
            this.i = i;
        }

        @Override
        public ReturnObject call() throws Exception {
            Pair<MetaQTL4MetaTrait, String> combo = combos.get(i);
            MetaQTL4MetaTrait gene = combo.getLeft();
//				int traitid = traitMap.get(gene);
            String snp = combo.getRight();
            Integer snpid = snpmap.get(snp);
            int nrtested = 0;
            ReturnObject o = new ReturnObject();
            if (snpid != null) {

                // compare allelic directions (no idea why this would flip)
                float[] outz = new float[perms.length];
                Pair<String, String> refalleles = snpalleles.get(snp);
                int refSampleSize = 0;
                for (int p = 0; p < perms.length; p++) {

                    BinaryMetaAnalysisDataset ds = perms[p];

                    // get zscores
                    Integer dssnpid = snpindex[p][snpid];

                    if (dssnpid != null) {

                        float[] zscores = ds.getZScores(dssnpid);
                        String dssnpalleles = ds.getAlleles(dssnpid);
                        String dssnpalleleassessed = ds.getAlleleAssessed(dssnpid);
                        int dssamplesize = ds.getSampleSize(dssnpid);
                        if (refSampleSize == 0) {
                            refSampleSize = dssamplesize;
                        }
                        Boolean flip = false;
                        if (refalleles == null) {
                            refalleles = new Pair<String, String>(dssnpalleles, dssnpalleleassessed);
                            snpalleles.put(snp, refalleles);
                        } else {
                            flip = BaseAnnot.flipalleles(refalleles.getLeft(), refalleles.getRight(), dssnpalleles, dssnpalleleassessed);
                            if (ds.getSampleSize(dssnpid) != refSampleSize) {
                                System.out.println("ERROR: expected " + refSampleSize + " but found " + ds.getSampleSize(dssnpid) + "\t" + snp + "\tperm: " + ds.getPermutation() + "\t" + p);
                                System.exit(-1);
                            }
                        }


                        if (flip != null) {
                            // get associated traits
                            MetaQTL4MetaTrait[] cisgene = ds.getCisProbes(dssnpid);
                            for (int t = 0; t < cisgene.length; t++) {
                                MetaQTL4MetaTrait c = cisgene[t];
                                if (c != null && c.equals(gene)) {
                                    float z = zscores[t];

                                    if (Float.isNaN(z)) {
                                        outz[p] = Float.NaN;
                                    } else {
                                        if (flip) {
                                            z *= -1;
                                        }
                                        outz[p] = z;
                                        nrtested++;
                                    }
                                }
                            }
                        }
                    }

                }

                // only write when there's full data available
                if (nrtested == perms.length) {
                    if (refSampleSize > 0) {
                        String outln = gene.getMetaTraitName() + "\t" + snp + "\t" + refalleles.getLeft() + "\t" + refalleles.getRight() + "\t" + refSampleSize;
//					out.writeln(outln);
//					bf.writeZ(outz);
                        o.outz = outz;
                        o.outln = outln;
                    } else {
                        String outln = gene.getMetaTraitName() + "\t" + snp + "\t" + snpid + "\t" + nrtested + "\t0 samples";
                        o.outln = outln;
                        if (refSampleSize == 0) {
                            System.out.println(outln);
                            System.exit(-1);

                        }


                    }
                    o.i = i;

                } else {
                    String outln = gene.getMetaTraitName() + "\t" + snp + "\t" + snpid + "\t" + nrtested;
                    o.outln = outln;
                    o.i = i;
                }
            } else {
                String outln = gene.getMetaTraitName() + "\t" + snp + "\t" + snpid + "\t" + nrtested;
                o.outln = outln;
                o.i = i;
            }
            return o;
        }
    }

    class BinaryDatasetInitializerTask implements Callable<BinaryMetaAnalysisDataset> {

        int permutation;
        int d;
        boolean loadsnpstats = true;
        AtomicInteger ctr;

        public BinaryDatasetInitializerTask(int permutation, int d, AtomicInteger ctr) {
            this.d = d;
            this.permutation = permutation;
            this.ctr = ctr;
        }

        @Override
        public BinaryMetaAnalysisDataset call() throws Exception {

            BinaryMetaAnalysisDataset ds = new BinaryMetaAnalysisDataset(settings.getDatasetlocations().get(d),
                    settings.getDatasetnames().get(d),
                    settings.getDatasetPrefix().get(d), permutation,
                    settings.getDatasetannotations().get(d), probeAnnotation, settings.getFeatureOccuranceScaleMaps().get(d),
                    loadsnpstats,
                    null);

            return ds;
        }
    }

    private ArrayList<Pair<MetaQTL4MetaTrait, String>> loadgenesnpcombos() throws IOException {
        System.out.println("Reading gene/snp combos from: " + settings.getGeneToSNP());
        ArrayList<Pair<MetaQTL4MetaTrait, String>> output = new ArrayList<>();
        TextFile tf = new TextFile(settings.getGeneToSNP(), TextFile.R);

        HashMap<String, MetaQTL4MetaTrait> traitHashMap = new HashMap<>();
        for (MetaQTL4MetaTrait t : traitList) {
            String name = t.getMetaTraitName();
            traitHashMap.put(name, t);
        }

        String[] elems = tf.readLineElems(TextFile.tab);

        while (elems != null) {
            MetaQTL4MetaTrait t = traitHashMap.get(elems[0]);

            output.add(new Pair<>(t, Strings.cache(elems[1])));
            if (output.size() % 250000 == 0) {
                System.out.print("\r" + output.size() + " combos read so far. " + getFreeMemory());
            }
            elems = tf.readLineElems(TextFile.tab);
        }
        System.out.println();
        System.out.println(output.size() + " gene / snp combos to process");
        tf.close();
        return output;
    }

}
