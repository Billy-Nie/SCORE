package structuredCoalescentNetwork.math;

public interface Euler2ndOrderBase {

    public void setup(int maxSize, int types, double epsilon, double max_step);

    public void init(double[] migration_rates, double[] coalescent_rates, double[] reassortment_rates, int lineages);

//    public void initWithIndicators(double[] migration_rates, int[] indicators, double[] coalescent_rates,
//	    double[] reassortment_rates, int lineages);

    public void calculateValues(double duration, double[] p, int length);

    default public void initAndcalculateValues(double[] migration_rates, double[] coalescent_rates,
	    double[] reassortment_rates, int lineages, double duration, double[] p, int length) {
	init(migration_rates, coalescent_rates, reassortment_rates, lineages);
	calculateValues(duration, p, length);
    }

    public void initAndcalculateValues(int ratesInterval, int lineages, double duration, double[] p, int length);

    public void setUpDynamics(double[][] coalescentRates, double[][] migrationRates, double[][] reassortment_rates,
	    int[][] indicators, double[] nextRateShift);

}
