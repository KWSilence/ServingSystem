package gui.tab;

import configs.SimulationConfig;
import gui.MainGUI;
import gui.SimulatorThread;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import system.component.Processor;
import system.component.Request;
import system.simulator.Simulator;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyzeTab implements TabCreator {
    private enum SelectorType {
        SOURCE, PROCESSOR, BUFFER
    }

    private enum SeriesType {
        REJECT_PROBABILITY, LIFE_TIME, PROCESSORS_USING_RATE
    }

    @FunctionalInterface
    private interface OnAnalyzeComplete {
        void analyzeComplete();
    }

    @FunctionalInterface
    private interface OnSeriesUpdate {
        void seriesUpdate(SeriesType type, double x, double y);
    }

    private final JPanel root;
    private final boolean debug;
    private ArrayList<SimulatorThread> simToAnalyze = new ArrayList<>();
    private Thread analyzeThread = null;

    public AnalyzeTab(LayoutManager layoutManager, boolean debug) {
        this.root = new JPanel(layoutManager);
        this.debug = debug;

        //[COM]{ELEMENT} Tab Analyze: reject probability chart
        JFreeChart rejectProbabilityChart = createChart("RejectProbability", "Count", "Probability", null);
        root.add(new ChartPanel(rejectProbabilityChart));
        //[COM]{ELEMENT} Tab Analyze: lifetime chart
        JFreeChart lifeTimeChart = createChart("LifeTime", "Count", "Time", null);
        root.add(new ChartPanel(lifeTimeChart));
        //[COM]{ELEMENT} Tab Analyze: processors using rate chart
        JFreeChart processorsUsingRateChart = createChart("ProcessorsUsingRate", "Count", "Rate", null);
        root.add(new ChartPanel(processorsUsingRateChart), "wrap");
        //[COM]{ELEMENT} Tab Analyze: selection of variable element combobox
        JComboBox<String> selectorCombobox = new JComboBox<>(new String[]{"Source", "Processor", "Buffer"});
        root.add(selectorCombobox);
        //[COM]{ELEMENT} Tab Analyze: "from" text field
        root.add(new JLabel("From"), "split 8");
        JTextField fromTextField = new JTextField("10");
        root.add(fromTextField);
        //[COM]{ELEMENT} Tab Analyze: "to" text field
        root.add(new JLabel("To"));
        JTextField toTextField = new JTextField("100");
        root.add(toTextField);
        //[COM]{ELEMENT} Tab Analyze: "lambda" text field
        root.add(new JLabel("Lambda"));
        JTextField lambdaTextField = new JTextField("1.0");
        root.add(lambdaTextField);
        //[COM]{ELEMENT} Tab Analyze: visualization step for charts text field -- threads count
        root.add(new JLabel("VStep"));
        JTextField visualStepTextField = new JTextField("5");
        root.add(visualStepTextField);
        //[COM]{ACTION} Tab Analyze: combobox change buffer->!lambda
        selectorCombobox.addActionListener(e -> lambdaTextField.setEnabled(selectorCombobox.getSelectedIndex() != 2));
        //[COM]{ELEMENT} Tab Analyze: stop button
        JButton stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        root.add(stopButton, "split 2");
        //[COM]{ELEMENT} Tab Analyze: start button
        JButton startButton = new JButton("Launch");
        root.add(startButton);
        //[COM]{ACTION} Tab Analyze: stop button
        stopButton.addActionListener(e -> {
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            stopAnalyze();
        });
        //[COM]{ACTION} Tab Analyze: start button
        startButton.addActionListener(e -> {
            simToAnalyze = new ArrayList<>();
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            SelectorType selector = SelectorType.values()[selectorCombobox.getSelectedIndex()];
            int minCount = Integer.parseInt(fromTextField.getText());
            int maxCount = Integer.parseInt(toTextField.getText());
            double lambda = Double.parseDouble(lambdaTextField.getText());
            int visualisationStep = Integer.parseInt(visualStepTextField.getText());

            Map<SeriesType, JFreeChart> charts = new HashMap<>(3);
            charts.put(SeriesType.REJECT_PROBABILITY, rejectProbabilityChart);
            charts.put(SeriesType.LIFE_TIME, lifeTimeChart);
            charts.put(SeriesType.PROCESSORS_USING_RATE, processorsUsingRateChart);

            String name = getSeriesName(selector, minCount, maxCount, lambda);
            Map<SeriesType, XYSeries> series = new HashMap<>(3);
            series.put(SeriesType.REJECT_PROBABILITY, new XYSeries(name));
            series.put(SeriesType.LIFE_TIME, new XYSeries(name));
            series.put(SeriesType.PROCESSORS_USING_RATE, new XYSeries(name));

            for (SeriesType type: charts.keySet()) {
                charts.get(type).getXYPlot().setDataset(new XYSeriesCollection(series.get(type)));
            }

            analyze(selector, minCount, maxCount, lambda, visualisationStep,
                    (type, index, value) -> {
                        XYSeries xySeries = series.get(type);
                        if (xySeries != null) {
                            xySeries.add(index, value);
                        }
                    },
                    () -> {
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                        stopAnalyze();
                    }
            );
        });
    }

    private JFreeChart createChart(String title, String xl, String yl, XYDataset ds) {
        return ChartFactory.createXYLineChart(title, xl, yl, ds, PlotOrientation.VERTICAL, true, true, true);
    }

    private void analyze(
            SelectorType selector,
            int minCount,
            int maxCount,
            double lambda,
            int visualisationStep,
            OnSeriesUpdate onSeriesUpdate,
            OnAnalyzeComplete onAnalyzeComplete
    ) {
        analyzeThread = new Thread() {
            private void checkInterruption() throws InterruptedException {
                if (isInterrupted()) {
                    throw new InterruptedException();
                }
            }

            @Override
            public void run() {
                ArrayList<SimulatorThread> buffer = new ArrayList<>();
                SimulationConfig.ConfigJSON config = SimulationConfig.readJSON(MainGUI.getDefaultConfigPath(debug));
                String name = getSeriesName(selector, minCount, maxCount, lambda);
                XYSeries[] series = new XYSeries[]{
                        new XYSeries(name),
                        new XYSeries(name),
                        new XYSeries(name)
                };
                try {
                    for (int i = minCount; i <= maxCount; i++) {
                        checkInterruption();
                        List<Double> sources = getSources(selector, i, config, lambda);
                        List<Double> processors = getProcessors(selector, i, config, lambda);
                        int bufferCapacity = getBufferCapacity(selector, i, config);
                        SimulationConfig.ConfigJSON configJSON = new SimulationConfig.ConfigJSON(
                                config.getRequestsCount(), bufferCapacity, sources, processors
                        );
                        Simulator tmpSimulator = new Simulator(new SimulationConfig(configJSON));
                        buffer.add(new SimulatorThread(tmpSimulator, true));
                        if (i >= maxCount || buffer.size() >= visualisationStep) {
                            checkInterruption();
                            simToAnalyze.addAll(buffer);
                            buffer.forEach(SimulatorThread::start);
                            int ind = 1;
                            for (SimulatorThread simulatorThread : buffer) {
                                simulatorThread.join();
                                checkInterruption();
                                Simulator simulator = simulatorThread.getSimulator();
                                int index = i - buffer.size() + ind;
                                addSeries(index, onSeriesUpdate, simulator, config.getRequestsCount());
                                ind++;
                            }
                            buffer.clear();
                            checkInterruption();
                            simToAnalyze.clear();
                        }
                    }
                } catch (InterruptedException exception) {
                    exception.printStackTrace();
                    Thread.currentThread().interrupt();
                } finally {
                    onAnalyzeComplete.analyzeComplete();
                }
            }
        };
        analyzeThread.start();
    }

    private void stopAnalyze() {
        if (analyzeThread != null) {
            analyzeThread.interrupt();
            analyzeThread = null;
        }
        if (simToAnalyze != null) {
            for (SimulatorThread simulator : simToAnalyze) {
                simulator.interrupt();
            }
            simToAnalyze.clear();
            simToAnalyze = null;
        }
    }

    private String getSeriesName(SelectorType selector, int minCount, int maxCount, double lambda) {
        String name;
        switch (selector) {
            case SOURCE -> name = "Source[" + minCount + ":" + maxCount + "], lambda=" + lambda;
            case PROCESSOR -> name = "Processor[" + minCount + ":" + maxCount + "], lambda=" + lambda;
            case BUFFER -> name = "BufferCapacity[" + minCount + ":" + maxCount + "]";
            default -> name = "";
        }
        return name;
    }

    private void addSeries(int index, OnSeriesUpdate onSeriesUpdate, Simulator simulator, int requestCount) {
        onSeriesUpdate.seriesUpdate(
                SeriesType.REJECT_PROBABILITY,
                index,
                ((double) simulator.getProductionManager().getFullRejectCount() / (double) requestCount)
        );
        double time = 0;
        for (List<Request> requests : simulator.getSelectionManager().getSuccessRequests()) {
            time += requests.stream().mapToDouble(Request::getLifeTime).sum();
        }
        onSeriesUpdate.seriesUpdate(
                SeriesType.LIFE_TIME,
                index,
                time / simulator.getSelectionManager().getFullSuccessCount()
        );
        time = 0;
        for (Processor p : simulator.getSelectionManager().getProcessors()) {
            time += p.getWorkTime() / simulator.getEndTime();
        }
        onSeriesUpdate.seriesUpdate(
                SeriesType.PROCESSORS_USING_RATE,
                index,
                time / simulator.getSelectionManager().getProcessors().size()
        );
    }

    private List<Double> getSources(SelectorType selector, int iter, SimulationConfig.ConfigJSON config, double val) {
        if (selector == SelectorType.SOURCE) {
            ArrayList<Double> sources = new ArrayList<>();
            for (int j = 0; j < iter; j++) {
                sources.add(val);
            }
            return sources;
        }
        return config.getSources();
    }

    private List<Double> getProcessors(SelectorType selector, int iter, SimulationConfig.ConfigJSON config, double val) {
        if (selector == SelectorType.PROCESSOR) {
            List<Double> processors = new ArrayList<>();
            for (int j = 0; j < iter; j++) {
                processors.add(val);
            }
            return processors;
        }
        return config.getProcessors();
    }

    private int getBufferCapacity(SelectorType selector, int iter, SimulationConfig.ConfigJSON config) {
        if (selector == SelectorType.BUFFER) {
            return iter;
        }
        return config.getBufferCapacity();
    }

    @Override
    public JPanel getRoot() {
        return root;
    }
}
