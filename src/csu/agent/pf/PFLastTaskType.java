package csu.agent.pf;

public class PFLastTaskType {
	
	public enum PFClusterLastTaskEnum {
		HELP_STUCK_HUMAN, 
		TRAVERSAL_REFUGE, 
		TRAVERSAL_CRITICAL_AREA, 
		TRAVERSAL_ENTRANCE, 
		CANNOT_TO_CLEAR, 
		NO_TAST
	}
	
	public enum PFCoincidentLastTaskEnum {
		COINCIDENT_CHECK_REFUGE,
		COINCIDENT_HELP_BURIED_AGENT,
		COINCIDENT_HELP_STUCKED_AGENT,
		COINCIDENT_CANNOT_TO_CLEAR,
		NO_TASK
	}
}
