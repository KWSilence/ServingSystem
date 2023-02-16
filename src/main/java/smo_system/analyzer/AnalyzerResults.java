package smo_system.analyzer;

public class AnalyzerResults {
    boolean isSource;
    int number = 0;

    int requestCount = 0;
    double rejectProbability = 0;
    double lifeTime = 0;
    double bufferTime = 0;
    double processTime = 0;
    double bufferTimeDispersion = 0;
    double processTimeDispersion = 0;

    double usageRate = 0;

    AnalyzerResults(boolean isSource) {
        this.isSource = isSource;
    }
}
