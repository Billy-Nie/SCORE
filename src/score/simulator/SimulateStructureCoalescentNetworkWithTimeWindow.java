package score.simulator;

import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.parameter.RealParameter;
import beast.evolution.alignment.TaxonSet;
import beast.evolution.tree.TraitSet;
import beast.evolution.tree.Tree;
import beast.util.Randomizer;
import coalre.network.Network;
import coalre.network.NetworkEdge;
import coalre.network.NetworkNode;

import java.util.*;
import java.util.stream.Collectors;
import org.javatuples.Pair;


public class SimulateStructureCoalescentNetworkWithTimeWindow extends Network {

    // array of reassortment rates for each state
    public Input<RealParameter> reassortmentRatesInput = new Input<>("reassortmentRate",
	    "Rate of reassortment for each state (per lineage per unit time)", Validate.REQUIRED);

	public Input<Double> scalarInput = new Input<>("scalar", "scalar fro reassortment rate", Validate.REQUIRED);

	public Input<Double> timeWindowInput = new Input<>("timeWindow", "length of time window after migration event", Validate.REQUIRED);

    // array of migration rates for each state
    public Input<RealParameter> migrationRatesInput = new Input<>("migrationRate",
	    "Rate of migration for each state (per lineage per unit time)", Validate.REQUIRED);

//    public Input<PopulationFunction> populationFunctionInput = new Input<>("populationModel",
//	    "Population model to use.", Validate.REQUIRED);

    public Input<RealParameter> coalescentRatesInput = new Input<>("coalescentRate",
			"Rate of coalescence for each state (per lineage per unit time)", Validate.REQUIRED);

    public Input<Integer> dimensionInput = new Input<>("dimension",
	    "the number of different states." + " if -1, it will use the number of different types ", -1);

    public Input<TraitSet> typeTraitInput = new Input<>("typeTrait", "Type trait set. ", Validate.REQUIRED);

    public Input<String> typesInput = new Input<>("types",
	    "input of the different types, can be helpful for multilocus data");

    public Input<List<Tree>> segmentTreesInput = new Input<>("segmentTree", "One or more segment trees to initialize.",
	    new ArrayList<>());

    public Input<Integer> nSegmentsInput = new Input<>("nSegments",
	    "Number of segments. Used if no segment trees are supplied.");

    public Input<TraitSet> traitSetInput = new Input<>("traitSet", "Trait set used to assign leaf ages.");

    public Input<Boolean> enableSegTreeUpdateInput = new Input<>("enableSegmentTreeUpdate",
	    "If false, segment tree objects won't be updated to agree with simulated " + "network. (Default true.)",
	    true);

    public Input<String> fileNameInput = new Input<>("fileName", "Name of file to write simulated network to.");

    public Input<Double> ploidyInput = new Input<>("ploidy",
	    "Ploidy (copy number) for this gene," + "typically a whole number or half (default is 1).", 1.0);

    final public Input<Boolean> ignoreMigrationNodes = new Input<>("ignoreMigrationNodes",
	    "Do not include migration nodes in network output", false);

    private RealParameter reassortmentRates;

	private double scalar;
	private double timeWindow;

    private RealParameter migrationRates;
//    private PopulationFunction populationFunction;
    private RealParameter coalescentRates;
    private RealParameter Ne;
    private final HashMap<String, Integer> typeNameToIndex = new HashMap<>();
	public final HashMap<Integer, String> typeIndexToName = new HashMap<>();

    private ArrayList<String> uniqueTypes;

    private enum MigrationType {
	symmetric, asymmetric
    }

    private MigrationType migrationType;

    private int nSegments;

    @Override
    public void initAndValidate() {

	if (nSegmentsInput.get() != null)
	    nSegments = nSegmentsInput.get();
	else
	    nSegments = segmentTreesInput.get().size();

//	populationFunction = populationFunctionInput.get();
	reassortmentRates = reassortmentRatesInput.get();
	scalar = scalarInput.get();
	timeWindow = timeWindowInput.get();
	migrationRates = migrationRatesInput.get();
	coalescentRates = coalescentRatesInput.get();

	if (nSegments == 0) {
	    throw new IllegalArgumentException("Need at least one segment!");
	}

	// Set up sample nodes:

	final List<NetworkNode> sampleNodes = new ArrayList<>();

	final TraitSet leafAgeTraitSet = traitSetInput.get();
	final TraitSet typeTraitSet = typeTraitInput.get();
	TaxonSet taxonSet;
	if (leafAgeTraitSet != null)
	    taxonSet = leafAgeTraitSet.taxaInput.get();
	else
	    taxonSet = typeTraitSet.taxaInput.get();

	if (taxonSet == null)
	    throw new IllegalArgumentException("Must define either a " + "trait set, type set or a taxon set.");

	final SortedSet<String> typeNameSet = new TreeSet<>();
	taxonSet.asStringList().forEach(n -> typeNameSet.add(typeTraitSet.getStringValue(n)));
	uniqueTypes = new ArrayList<>(typeNameSet);

	for (int i = 0; i < uniqueTypes.size(); i++) {
	    typeNameToIndex.put(uniqueTypes.get(i), i);
	    typeIndexToName.put(i, uniqueTypes.get(i));
	}
	
	if (coalescentRates.getDimension() != uniqueTypes.size())
		coalescentRates.setDimension(uniqueTypes.size());
	
	if (reassortmentRates.getDimension() != uniqueTypes.size())
		reassortmentRates.setDimension(uniqueTypes.size());

		final int migDim = dimensionInput.get() != -1 ? dimensionInput.get() * (dimensionInput.get() - 1)
		: uniqueTypes.size() * (uniqueTypes.size() - 1);


	if (migDim == migrationRates.getDimension()) {
	    migrationType = MigrationType.asymmetric;
	} else if (migDim / 2 == migrationRates.getDimension()) {
	    migrationType = MigrationType.symmetric;
	} else {
	    migrationType = MigrationType.asymmetric;
	    System.err.println("Wrong number of migration elements, assume asymmetric migration:");
	    System.err.println("the dimension of " + migrationRates.getID() + " is set to " + migDim);
	    migrationRates.setDimension(migDim);
	}

	for (int taxonIndex = 0; taxonIndex < taxonSet.getTaxonCount(); taxonIndex++) {
	    final String taxonName = taxonSet.getTaxonId(taxonIndex);

	    final NetworkNode sampleNode = new NetworkNode();
	    sampleNode.setTaxonLabel(taxonName);
	    sampleNode.setTaxonIndex(taxonIndex);

	    if (leafAgeTraitSet != null)
		sampleNode.setHeight(leafAgeTraitSet.getValue(taxonName));
	    else
		sampleNode.setHeight(0.0);

	    final String typeName = typeTraitSet.getStringValue(taxonName);
	    sampleNode.setTypeLabel(typeName);
	    sampleNode.setTypeIndex(typeNameToIndex.get(typeTraitSet.getStringValue(taxonName)));

	    sampleNodes.add(sampleNode);
	}

	// Perform network simulation:
	simulateNetwork(sampleNodes);

	// Update segment trees:
	if (enableSegTreeUpdateInput.get()) {
	    for (int segIdx = 0; segIdx < nSegments; segIdx++) {
		Tree segmentTree = segmentTreesInput.get().get(segIdx);
		updateSegmentTree(segmentTree, segIdx);
		segmentTree.setEverythingDirty(false);
	    }
	}

	if (ignoreMigrationNodes.get())
	    removeMigrationNodes();
    }

    /**
     * Simulate network under structured coalescent with reassortment model.
     * 
     * @param sampleNodes network nodes corresponding to samples.
     */
    public void simulateNetwork(List<NetworkNode> sampleNodes) {

	final List<NetworkNode> remainingSampleNodes = new ArrayList<>(sampleNodes);

	// #####################################
	// extant lineages have to be sorted by state id
	// #####################################

	final HashMap<Integer, List<Pair>> extantLineages = new HashMap<Integer, List<Pair>>(
		uniqueTypes.size() * 2);

	for (int i = 0; i < uniqueTypes.size() * 2; i++) {
	    extantLineages.put(i, new ArrayList<>());
	}

	remainingSampleNodes.sort(Comparator.comparingDouble(NetworkNode::getHeight));

	double currentTime = 0;
	double timeUntilNextSample;
	List<List<Pair>> remaining;
	do {

		clearTimeWindow(extantLineages, uniqueTypes.size(), currentTime);

	    // get the timing of the next sampling event
	    if (!remainingSampleNodes.isEmpty()) {
		timeUntilNextSample = remainingSampleNodes.get(0).getHeight() - currentTime;
	    } else {
		timeUntilNextSample = Double.POSITIVE_INFINITY;
	    }

	    // TODO make work for different pop models
	    // assume fixed population for now, so transformation like this not needed:
//	     double currentTransformedTime = populationFunction.getIntensity(currentTime);
//	     double transformedTimeToNextCoal = k>=2 ? Randomizer.nextExponential(0.5*k*(k-1)) : Double.POSITIVE_INFINITY;
//	     double timeToNextCoal = populationFunction.getInverseIntensity(
//	     transformedTimeToNextCoal + currentTransformedTime) - currentTime;

	    double minCoal = Double.POSITIVE_INFINITY;
	    double minReassort = Double.POSITIVE_INFINITY;
	    double minMigration = Double.POSITIVE_INFINITY;

	    int typeIndexCoal = -1, typeIndexReassortment = -1, typeIndexMigrationFrom = -1, typeIndexMigrationTo = -1;

	    int c = 0;
	    for (int i = 0; i < uniqueTypes.size() * 2; i++) {
			boolean psudoTypeFlag = false;
			if (i % 2 == 0) {
				psudoTypeFlag = false;
			} else {
				psudoTypeFlag = true;
			}

			// how many lineages are in this state
			final int k_ = extantLineages.get(i).size();
			int k_psudoType;
			if (!psudoTypeFlag) {
				k_psudoType = extantLineages.get(i + 1).size(); // Get the # of lineages in type i'
			} else {
				k_psudoType = extantLineages.get(i - 1).size(); // Get the # of lineages in type i
			}


			if ((k_ + k_psudoType) >= 2 && !psudoTypeFlag) { // !psudoTypeFlag is added here to prevent sampling coalescent twice (one for type i and one for type i')
				// coalescent event for event i as a whole (including type i and type i')
				final double timeToNextCoal = Randomizer
					.nextExponential(0.5 * (k_ + k_psudoType) * ((k_ + k_psudoType) - 1) * coalescentRates.getArrayValue(i));
				if (timeToNextCoal < minCoal) {
				minCoal = timeToNextCoal;
				typeIndexCoal = i;
				}
			}

			if (k_ >= 1) {
				double timeToNextReass;
				if (!psudoTypeFlag) {
					timeToNextReass = Randomizer.nextExponential(k_ * reassortmentRates.getArrayValue(i)); // type i, use normal reassortment rate
				} else {
					timeToNextReass = Randomizer.nextExponential(k_ * reassortmentRates.getArrayValue(i - 1) * scalar); // type i', use scaled reassortment rate
				}

				if (timeToNextReass < minReassort) {
				minReassort = timeToNextReass;
				typeIndexReassortment = i;
				}

				if (!psudoTypeFlag) {
					for (int j = 1; j < uniqueTypes.size() * 2; j+=2) { // one can only migrate to pseudotype
						if (i != (j-1)) {
							final double timeToNextMigration = Randomizer.nextExponential(k_ * migrationRates.getArrayValue(c));
							c++;
							if (migrationType == MigrationType.symmetric)
								c %= migrationRates.getDimension();
							if (timeToNextMigration < minMigration) {
								minMigration = timeToNextMigration;
								typeIndexMigrationFrom = i;
								typeIndexMigrationTo = j;
							}
						}
					}
				} else {
					for (int j = 1; j < uniqueTypes.size() * 2; j+=2) {
						if (i != j) {
							final double timeToNextMigration = Randomizer.nextExponential(k_ * migrationRates.getArrayValue(c));
							c++;
							if (migrationType == MigrationType.symmetric)
								c %= migrationRates.getDimension();
							if (timeToNextMigration < minMigration) {
								minMigration = timeToNextMigration;
								typeIndexMigrationFrom = i;
								typeIndexMigrationTo = j;
							}
						}
					}
				}
			}
		}

	    // next event time
	    double timeUntilNextEvent = Math.min(minCoal, minReassort);
	    timeUntilNextEvent = Math.min(timeUntilNextEvent, minMigration);
	    if (timeUntilNextEvent < timeUntilNextSample) {
			currentTime += timeUntilNextEvent;
			if (timeUntilNextEvent == minCoal)
				coalesce(currentTime, extantLineages, typeIndexCoal);
			else if (timeUntilNextEvent == minReassort)
				reassort(currentTime, extantLineages, typeIndexReassortment);
			else
				migrate(currentTime, extantLineages, typeIndexMigrationFrom, typeIndexMigrationTo, timeWindow);

	    } else {
			currentTime += timeUntilNextSample;
			sample(remainingSampleNodes, extantLineages);
	    }

	    remaining = extantLineages.values().stream().filter(l -> l.size() >= 1).collect(Collectors.toList());

	} while ((remaining.size() > 1 || remaining.get(0).size() > 1) || !remainingSampleNodes.isEmpty());

	final List<List<Pair>> root = extantLineages.values().stream().filter(l -> l.size() == 1)
		.collect(Collectors.toList());
	if (root.size() > 1)
	    System.err.println("More than one root edge");
	setRootEdge((NetworkEdge) root.get(0).get(0).getValue0());
    }

    private void sample(List<NetworkNode> remainingSampleNodes, HashMap<Integer, List<Pair>> extantLineages) {
		// sample the network node
		final NetworkNode n = remainingSampleNodes.get(0);

		// Create corresponding lineage
		final BitSet hasSegs = new BitSet();
		hasSegs.set(0, nSegments);
		final NetworkEdge lineage = new NetworkEdge(null, n, hasSegs);
		final int id = n.getTypeIndex();
		final Pair<NetworkEdge, Double> lineage_pair = Pair.with(lineage, -1.0);
		extantLineages.get(id * 2).add(lineage_pair);
		n.addParentEdge(lineage);

		remainingSampleNodes.remove(0);
    }

    private void coalesce(double coalescentTime, HashMap<Integer, List<Pair>> extantLineages, int stateIdCoal) {
		// Sample the pair of lineages that are coalescing:

		// Uniformly sample from type stateIdCoal and stateIdCoal'
		final int randomNumber = Randomizer.nextInt(extantLineages.get(stateIdCoal).size() + extantLineages.get(stateIdCoal + 1).size());

		boolean lineage1PseudoTypeFlag = false;

		Pair<NetworkEdge, Double> lineage1;
		if (randomNumber < extantLineages.get(stateIdCoal).size()) {
			lineage1 = extantLineages.get(stateIdCoal).get(randomNumber);
			lineage1PseudoTypeFlag = false;
		} else {
			lineage1 = extantLineages.get(stateIdCoal + 1).get(randomNumber - extantLineages.get(stateIdCoal).size());
			lineage1PseudoTypeFlag = true;
		}

		int randomNumber2;
		boolean linege2PseudoTypeFlag = false;
		Pair<NetworkEdge, Double> lineage2;
		do {
			randomNumber2 = Randomizer.nextInt(extantLineages.get(stateIdCoal).size() + extantLineages.get(stateIdCoal + 1).size());
			if(randomNumber2 < extantLineages.get(stateIdCoal).size()) {
				lineage2 = extantLineages.get(stateIdCoal).get(randomNumber2);
				linege2PseudoTypeFlag = false;
			} else {
				lineage2 = extantLineages.get(stateIdCoal + 1).get(randomNumber2 - extantLineages.get(stateIdCoal).size());
				linege2PseudoTypeFlag = true;
			}

		} while (lineage1 == lineage2);

		// Create coalescent node
		final NetworkNode coalescentNode = new NetworkNode();
		coalescentNode.setHeight(coalescentTime).addChildEdge(lineage1.getValue0()).addChildEdge(lineage2.getValue0());
		coalescentNode.setTypeIndex(stateIdCoal / 2);
		coalescentNode.setTypeLabel(uniqueTypes.get(stateIdCoal / 2));
		lineage1.getValue0().parentNode = coalescentNode;
		lineage2.getValue0().parentNode = coalescentNode;

		// Merge segment flags:
		final BitSet hasSegments = new BitSet();
		hasSegments.or(lineage1.getValue0().hasSegments);
		hasSegments.or(lineage2.getValue0().hasSegments);

		// Create new lineage
		final NetworkEdge lineage = new NetworkEdge(null, coalescentNode, hasSegments);
		coalescentNode.addParentEdge(lineage);
		double newLineageTimeWindow = Math.max(lineage1.getValue1(), lineage2.getValue1());
		boolean newLineagePsudoTypeFlag = false;
		if (newLineageTimeWindow > -1) {
			newLineagePsudoTypeFlag = true;
		}
		final Pair<NetworkEdge, Double> newLineage = Pair.with(lineage, newLineageTimeWindow);

		if (lineage1PseudoTypeFlag) {
			extantLineages.get(stateIdCoal + 1).remove(lineage1);
		} else {
			extantLineages.get(stateIdCoal).remove(lineage1);
		}

		if (linege2PseudoTypeFlag) {
			extantLineages.get(stateIdCoal + 1).remove(lineage2);
		} else {
			extantLineages.get(stateIdCoal).remove(lineage2);
		}

		if (newLineagePsudoTypeFlag) {
			extantLineages.get(stateIdCoal + 1).add(newLineage);
		} else {
			extantLineages.get(stateIdCoal).add(newLineage);
		}
    }

    private void reassort(double reassortmentTime, HashMap<Integer, List<Pair>> extantLineages,
	    int stateIdReassortment) {
		final Pair<NetworkEdge, Double> lineage = extantLineages.get(stateIdReassortment).get(Randomizer.nextInt(extantLineages.get(stateIdReassortment).size()));

		final BitSet hasSegs_left = new BitSet();
		final BitSet hasSegs_right = new BitSet();

		for (int segIdx = lineage.getValue0().hasSegments.nextSetBit(0); segIdx != -1; segIdx = lineage.getValue0().hasSegments
			.nextSetBit(segIdx + 1)) {
			if (Randomizer.nextBoolean()) {
			hasSegs_left.set(segIdx);
			} else {
			hasSegs_right.set(segIdx);
			}
		}

		// Stop here if reassortment event is unobservable
		if (hasSegs_left.cardinality() == 0 || hasSegs_right.cardinality() == 0)
			return;

		// Create reassortment node
		final NetworkNode node = new NetworkNode();
		node.setHeight(reassortmentTime).addChildEdge(lineage.getValue0());
		node.setTypeIndex(lineage.getValue0().childNode.getTypeIndex());
		node.setTypeLabel(lineage.getValue0().childNode.getTypeLabel());

		// Create reassortment lineages
		final NetworkEdge leftLineage = new NetworkEdge(null, node, hasSegs_left);
		final NetworkEdge rightLineage = new NetworkEdge(null, node, hasSegs_right);
		node.addParentEdge(leftLineage);
		node.addParentEdge(rightLineage);
		final Pair<NetworkEdge, Double> leftLineagePair = Pair.with(leftLineage, lineage.getValue1());
		final Pair<NetworkEdge, Double> rightLineagePair = Pair.with(rightLineage, lineage.getValue1());

		extantLineages.get(stateIdReassortment).remove(lineage);
		extantLineages.get(stateIdReassortment).add(leftLineagePair);
		extantLineages.get(stateIdReassortment).add(rightLineagePair);
    }

    private void migrate(double migrationTime, HashMap<Integer, List<Pair>> extantLineages,
	    int stateIdMigrationFrom, int stateIdMigrationTo, double timeWindow) {
		// Sample the lineage for migration:
		final Pair<NetworkEdge, Double> lineage = extantLineages.get(stateIdMigrationFrom).get(Randomizer.nextInt(extantLineages.get(stateIdMigrationFrom).size()));

		final NetworkNode migrationPoint = new NetworkNode();
		final NetworkEdge newParentEdge = new NetworkEdge();
		// NetworkEdge newParentEdge = lineage.getCopy();
		newParentEdge.hasSegments = lineage.getValue0().hasSegments;

		migrationPoint.setHeight(migrationTime);
		migrationPoint.addParentEdge(newParentEdge);

		migrationPoint.addChildEdge(lineage.getValue0());

		migrationPoint.setTypeIndex(stateIdMigrationTo);
		migrationPoint.setTypeLabel(uniqueTypes.get(stateIdMigrationTo));

		Pair<NetworkEdge, Double> newParentEdgePair = Pair.with(newParentEdge, lineage.getValue1());
		extantLineages.get(stateIdMigrationFrom).remove(lineage);
		newParentEdgePair.setAt1(migrationTime + timeWindow);

		extantLineages.get(stateIdMigrationTo).add(newParentEdgePair);
    }

	// clear up those lineages whose time window has passed
	private void clearTimeWindow(HashMap<Integer, List<Pair>> extantLineages, double typeNumber, double currentTime) {
		for(int i = 1; i < typeNumber * 2; i += 2){
			int size = extantLineages.get(i).size();
			for(int j = 0; j < size; j++) {
				Pair<NetworkEdge, Double> lineage = extantLineages.get(i).get(j);
				final double timeWindow = lineage.getValue1();
				if (currentTime > timeWindow && timeWindow != -1){
					Pair<NetworkEdge, Double> lineageNew = Pair.with(lineage.getValue0(), -1.0);
					extantLineages.get(i).remove(lineage);
					extantLineages.get(i - 1).add(lineageNew);
				}
			}

		}

	}

    private void removeMigrationNodes() {

	List<NetworkNode> migrationNodes = this.getNodes().stream().filter(n -> n.getParentCount() == 1)
		.filter(n -> n.getChildCount() == 1).sorted(Comparator.comparing(NetworkNode::getHeight))
		.collect(Collectors.toList());

	Collections.reverse(migrationNodes);

	for (NetworkNode m : migrationNodes) {
	    NetworkEdge parentEdge = m.getParentEdges().get(0);
	    NetworkEdge childEdge = m.getChildEdges().get(0);

	    NetworkNode newChildNode = null;
//			while (newChildNode == null) {
	    if (childEdge.childNode.getChildCount() > 1 || childEdge.childNode.getParentCount() > 1
		    || childEdge.childNode.isLeaf()) {
		newChildNode = childEdge.childNode;
		m.removeChildEdge(childEdge);
		m.removeParentEdge(parentEdge);
		newChildNode.addParentEdge(parentEdge);
		newChildNode.removeParentEdge(childEdge);

	    } else {

		childEdge = childEdge.childNode.getChildEdges().get(0);
		m.removeChildEdge(childEdge.parentNode.getParentEdges().get(0));
		m.removeParentEdge(parentEdge);
		childEdge.parentNode.removeParentEdge(childEdge.parentNode.getParentEdges().get(0));
		childEdge.parentNode.addParentEdge(parentEdge);
		m = childEdge.parentNode;
		parentEdge = m.getParentEdges().get(0);
	    }
//			}

	}

    }
}