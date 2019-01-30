package structuredCoalescentNetwork.math;

import org.apache.commons.math3.ode.FirstOrderDifferentialEquations;
import org.apache.commons.math3.ode.FirstOrderIntegrator;
import org.apache.commons.math3.ode.nonstiff.ClassicalRungeKuttaIntegrator;

import beast.core.Description;


@Description("")
public class ode_integrator implements FirstOrderDifferentialEquations {

	double[] migration_rates;
	double[] coalescent_rates;
	double[] reassortment_rates;
	double probs;
    int lineages;
    int types;
    int dimension;
    Integer[][] connectivity;
    Integer[][] sums;
    Integer[][] lineage_type;
    Integer[] n_segs;
    
    boolean belowzero = false;

    // constructor
    public ode_integrator(double[] migration_rates, double[] coalescent_rates, double[] reassortment_rates, int lineages,
    		int types, Integer[][] connectivity, Integer[][] sums, Integer[][] lineage_type, Integer[] n_segs){
        this.migration_rates = migration_rates;
        this.coalescent_rates = coalescent_rates;
        this.reassortment_rates = reassortment_rates;
        this.lineages = lineages;
        this.types = types;
        belowzero = false;
        this.dimension = sums.length;
        this.connectivity = connectivity;
        this.sums = sums;
        this.n_segs = n_segs;
        this.lineage_type = lineage_type;
    }

    public int getDimension() {
        return dimension;
    }

    
    public void computeDerivatives(double t, double[] p, double[] pDot) {
    	// Calculates the change in probability of being in a state due to coalescence
    	for (int i = 0; i < p.length; i++){
    		pDot[i] = 0.0;
    		for (int s = 0; s < types; s++)
    			pDot[i] -= (sums[i][s]-1)*sums[i][s]*coalescent_rates[s];
    		
    		pDot[i] *= p[i];
    		// Stop the run if any configuration has a probability of lower than 0 of still existing
    		// normally the case when the integration time steps are too large
    		if (p[i]<0){
    			System.err.println("joint prob below 0");
    			System.exit(0);
    		}
    	}
    	// Calculate the change in the probability of being in a configuration due to migration
    	for (int i = 0; i < p.length; i++){
    		for (int j = 0; j < p.length; j++){
    			if (connectivity[i][j]!=null){
    				double m = p[i]*migration_rates[connectivity[i][j]];
    				pDot[i] -= m;
    				pDot[j] += m;
    			}
    		}
    	}
    
    	
    	for (int i=0; i < p.length; i++) {
    		for (int s = 0; s < types; s++) {
    			for (int j=0; j <lineages; j++) {
    				if (lineage_type[i][j] == s) {
    					pDot[i] -= (1-Math.pow(0.5, n_segs[j]-1))*reassortment_rates[s]*p[i];
    				}
    			}
    		}
    	}
    }
        
    public static void main(String[] args) throws Exception{
        // 2d test
    	double[] migration_rates = {0.01, 0.001};
    	double[] coalescent_rates = {1.0, 1.0};
    	double[] reassortment_rates = {1.0, 1.0};
        int lineages = 2;
        int types = 2;
        /*
         * 0	1	1	0
         *	1	0	0	1
         *	1	0	0	1
         *	0	1	1	0
         *
         */
        Integer[][] con = {{null,0,0,null},{1,null,null,0},{1,null,null,0},{null,1,1,null}};
        Integer[][] sums = {{2,0},{1,1},{1,1},{0,2}};
        Integer[][] lineage_type = {{0,0},{0,1},{1,0},{1,1}};
        Integer[] n_segs = {1,2};

        FirstOrderIntegrator integrator = new ClassicalRungeKuttaIntegrator(0.01);
        FirstOrderDifferentialEquations ode = new ode_integrator(migration_rates, coalescent_rates, reassortment_rates, lineages , types, con, sums, lineage_type, n_segs);
        double[] y0 = new double[]{0,1,0,0};
        double[] y = new double[4];
    	integrator.integrate(ode, 0, y0, 30, y);

        System.out.println("Solution: " +y[0]+" "+y[1] + " " +y[2] + " " +y[3]);
    }

}