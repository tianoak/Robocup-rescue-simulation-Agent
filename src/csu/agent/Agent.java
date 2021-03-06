package csu.agent;

import java.awt.Shape;
import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import csu.Viewer.SelectedObject;
import csu.Viewer.layers.CSU_RoadLayer;
import csu.agent.fb.FireBrigadeAgent;
import csu.common.TimeOutException;
import csu.model.AdvancedWorldModel;
import csu.model.AgentConstants;
import csu.model.object.CSURoad;
import csu.standard.Ruler;
import csu.standard.simplePartition.GroupingType;
import rescuecore2.messages.Command;
import rescuecore2.misc.Pair;
import rescuecore2.standard.components.StandardAgent;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Hydrant;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.standard.messages.StandardMessageURN;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;

/**
 * 服务器的仿真过程是不断重复以下步骤的。对伊第一个周期，第一和第二步被跳过不执行。
 * <p>
 * 1. 服务器的kernel向每个RCR Agent发送它们独有的视觉和听觉信息，分别对应与think函数的changed和heard两个参数。
 * <p>
 * 2. 每个RCR Agent在thinkTime之内向kernel发送一个动作命令。
 * <p>
 * 3. kernel将接受到的动作命令发送个每个子仿真器
 * <p>
 * 4. 子仿真器根据接受到的动作命令作出相应的操作，并向kernel发送更新后的灾难空间的状况。
 * <p>
 * 5. kernel整合所有接受到的更新后灾难空间状态，并发送给viewer显示。
 * <p>
 * 6. kernel将仿真周期向前推进一步。
 * 
 * @author appreciation - csu
 * 
 * @param <E>
 *            StandardWorldModel
 */

public abstract class Agent<E extends StandardEntity> extends StandardAgent<E> {
	/**
	 * 用于监控代码的运行时间，它代表think()函数开始运行的那一个时刻。
	 * 
	 * @see csu.common.TimeOutException
	 */
	protected long preTime;  
	/**
	 * 用于监控代码的运行时间，它表示think()函数中某个特定操作完成后的时间。它与preTime的差值就表示代码从think开始
	 * 到这个特定操作结束所用的时间。
	 * 
	 * @see csu.common.TimeOutException
	 */
	protected long afterTime;
	
	/**
	 * 世界模型，相当与agent的记忆，它记录了agent对当前地图的所有认知。
	 */
	protected AdvancedWorldModel world;
	
	/**
	 * 当前的周期数。
	 */
	protected int time;
	
	/**
	 * agent当前周期能看到的东西全部记录在ChangeSet中。它们会自动跟新到agent的世界模型中去(merge()).
	 * <P>
	 * agent的视觉模型是模仿人的视线的，与人的视线一样，它有一个极限范围，这个范围是30m（服务器是以mm为单位
	 * 的，所以在config中看到的值是30000）。超过的这个方位的东西是看不到的。同时，视线还会受到墙壁的阻挡，
	 * 如果实现遇到墙壁，那么墙壁后的东西agent就看不到，尽管那些东西在30m范围内。
	 */
	protected ChangeSet changed;
	
	/**
	 * 在一个仿真周期中，一个智能体可以发送一条或多条命令，但kernel只会处理他接受到的最后一条命令。也就是说，
	 * kernel在一个周期内，对于每个agent，它都只处理一条命令。
	 * <p>
	 * 在代码中，我们把命令处理成一种异常，并把高优先级的命令排在前面。所以在agent的发送第一条命令后，就会被
	 * try..catch捕获，从而结束当前周期的决策操作(preAct() & act())，转入通信操作模块(afterAct())。
	 * <p>
	 * 这个变量用于记录每个周期这个智能体发送的命令。
	 */
	protected Map<Integer, StandardMessageURN> commandHistory = new TreeMap<>();
	
	/**
	 * 用于记录agent的location属性，也就是agent在某个周期所处的x和y坐标。
	 */
	protected Map<Integer, Pair<Integer, Integer>> locationHistory= new TreeMap<>();
	
	/**
	 * 用于记录agent的position属性。对于一个智能体，它必然会站在一个地方，这个地方可以是路，建筑物也可以是AT
	 * (当市民被AT装载的时候，市民的position就是装载它的那个AT)。所一对于position，它必然是一个实体(entity),
	 * 这里我们记录这个实体的ID。
	 */
	protected Map<Integer, EntityID> positionHistory = new TreeMap<Integer, EntityID>();
	
	/**
	 * 当前地图的总仿真周期数，由config决定。由于agent没有从config中获取这个值的权限，它一直是默认值300.
	 * 所以在使用的时候要稍微注意一下。
	 */
	protected int timestep;
	
	/**
	 * 每个周期中，智能体可用的思考时间。agent必须在这个时间内做完决策并向服务器发送命令。超过了这个时间发送
	 * 的命令，服务器不会作出任何响应。所以在代码实现的过程中，清注意代码的计算量。
	 * 
	 * @see csu.common.TimeOutException
	 */
	protected long thinkTime;	
	
/* ------------------------ initialize(), postConnect() and createWorldModel() --------------------------- */
	/**
	 * agent连接时的所有初始化操作。
	 */
	protected void initialize() {
		// index all entities in this world model
		model.indexClass(StandardEntityURN.AMBULANCE_TEAM, 
				StandardEntityURN.FIRE_BRIGADE, 
				StandardEntityURN.POLICE_FORCE, 
				StandardEntityURN.BUILDING, 
				StandardEntityURN.AMBULANCE_CENTRE, 
				StandardEntityURN.FIRE_STATION, 
				StandardEntityURN.POLICE_OFFICE, 
				StandardEntityURN.ROAD, 
				StandardEntityURN.REFUGE, 
				StandardEntityURN.BLOCKADE, 
				StandardEntityURN.CIVILIAN,
				StandardEntityURN.GAS_STATION,
				StandardEntityURN.HYDRANT);
		
		world = (AdvancedWorldModel) model;	
		world.initialize(this, config, getGroupingType());
		
		timestep = world.getConfig().timestep;
		thinkTime = world.getConfig().thinkTime;
		random = new Random(getID().getValue());
		
		System.out.println(getID());
	}
	
	/**
	 * agent向服务器连接时的所有操作，可认为是初始化操作，具体操作全部由代码中的initialize()实现。
	 */
	@Override
	protected void postConnect() {
		super.postConnect();
		initialize();
	}
	
	/**
	 * 这个方法非常重要。如果你没有重写这个函数，那么StandardAgent中的createWorldModel()
	 * 就会被调用，他会创作一个StandardWorldModel的实例。而我们自己写的AdvancedWorldModel是
	 * 继承自StandardWorldModel。当你把StandardWorldModel实例转为AdvancedWorldModel实例
	 * 时，会抛出异常。这就意味着，你没有用上AdvancedWorldModel，那么代码基本上也就废掉了。
	 */
	@Override
	protected StandardWorldModel createWorldModel() {
		return new AdvancedWorldModel();
	}
	
	
/* ---------------------------------------------- Think Method -------------------------------------------- */
	/**
	 * 该方法主要做一些決策前的更新操作。具体更新操作请看各子类的具体实现。
	 */
	protected void prepareForAct() throws TimeOutException{}
	
	/**
	 * 该方法为实际的决策模块。具体请看各子类。
	 * 
	 * @throws ActionCommandException
	 * @throws TimeOutException
	 */
	protected abstract void act() throws ActionCommandException, TimeOutException;
	
	/**
	 * 这个方法用于向其他agent发送radio和voice消息，属于通信操作。具体请看CommunicationAgent.afterAct()。
	 */
	 protected void afterAct() {}	 
	 
	/**
	 * 这个方法用于接受其他agent发送过来的radio和voice消息.具体请看CommunicationAgent.hear()。
	 * 
	 * @param heard
	 *            所有接受到的消息
	 */
	protected abstract void hear(Collection<Command> heard);

	/**
	 * 每个新的周期开始，都会调用think函数。可以把think认为是整个代码的入口，除了初始化以外的所有操作都是
	 * 为了扩展think。
	 * 
	 * @param time
	 *            当前周期数
	 * @param changed
	 *            当前视觉模型感知到的entity
	 * @param heard
	 *            接受到的radio和voice消息
	 */
	@Override
	protected void think(int time, ChangeSet changed, Collection<Command> heard) { 
		if (AgentConstants.LAUNCH_VIEWER) {
			SelectedObject.renderBuildingValueKey = false;
			
			if (world.getAgent().getID().getValue() == 485278126) {
				CSU_RoadLayer.selfL = null;
				CSU_RoadLayer.road = null;
				CSU_RoadLayer.dir = null;                                                                                                                                                                                                                       
				
				CSU_RoadLayer.repairDistance = world.getConfig().repairDistance;
			}
			
			CSU_RoadLayer.openParts.clear();
		}
		
		world.setThinkStartTime(System.currentTimeMillis());
		this.time = time;
		world.setTime(time);
		this.changed = changed;

		try {
			hear(heard);
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}

		try {
			try {
				prepareForAct();
			} catch (Exception e) {
				e.printStackTrace(System.out);
			}

			if (world.getConfig().ignoreUntil <= time) {
				act();
				isThinkTimeOver("after act");
				rest();
			}
		} catch (ActionCommandException ace) {
			commandHistory.put(time, ace.message);
		} catch (TimeOutException toe) {
			System.out.println("Time Out Exception message is: " + toe.getMessage());
			commandHistory.put(time, StandardMessageURN.AK_REST);
			world.setExceptionMessage(toe.getMessage());
		} catch (Exception e) {
			commandHistory.put(time, StandardMessageURN.AK_REST);
			e.printStackTrace(System.out);
			System.err.println("Exception : time = " + time + " id = " + me().getID() + " ----- think()");
		}

		try {
			afterAct();
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}
	
	/**
	 * 用于验证代码是否计算超时。需要将这个函数手动添加到某个特定的操作之后。
	 * 
	 * @param str
	 *            异常抛出时的提示文字
	 * @return 当某个特定操作没有超时是，返回false
	 * @throws TimeOutException
	 */
	public boolean isThinkTimeOver(String str) throws TimeOutException {
		long thinkTimeThreshold;
		if (AgentConstants.IS_DEBUG) {
			thinkTimeThreshold = (long)1000000000;   ///time
		} else {
			thinkTimeThreshold = (long)(thinkTime * 0.9);
		}
		if (System.currentTimeMillis() - world.getThinkStartTime() > thinkTimeThreshold)
			throw new TimeOutException(str);
		return false;
	}
	
	
	
	
	
/* ------------------------------------------------------------------------------------------------------- */
	/**
	 * 有两种智能体，platoon agent和center agent， platoon agent和center agent都是RCR agent。
	 * platoon agent控制一个human实体，center agent控制一个building实体。这个方法用于返回一个
	 * platoon agent控制的human实体。对于center agent，返回null。
	 * <p>
	 * RCR Agent ----- RoboCup Rescue Agent
	 * 
	 * @return 一个platoon agent控制的human实体
	 */
	public Human getMeAsHuman() {
		Human me = null;
        StandardEntity entity = world.getEntity(me().getID());
        if (entity instanceof Human)
        	me = (Human) entity;
        return me;
    }

	/**
	 * 对于center agent，它控制的实体本身就是一个building，所以它的位置就是它所控制的building实体。
	 * 而对于platton agent，它控制的实体是一个human，human必须站在一个entity上(building, road, 
	 * or ambulance team).
	 * <p>
	 * 注意：
	 * 在代码中，location和position这两个单词的意思没有刻意去区分，可能有一点混乱。所以在使用的时候，要
	 * 看清楚返回类型，根据返回类型是xy坐标还是entity来确定使用那个方法。
	 * 
	 * @return 当前RCR Agent所在的位置
	 */
	@Override
	public StandardEntity location() {
		E me = me();// AbstratEntity
		if (me instanceof Human) {
			return ((Human) me).getPosition(world);
		}
		return me;
	}
	
	 /**
	  * Rest命令。
	  * 
	  * @throws ActionCommandException
	  */
	protected void rest() throws ActionCommandException {
		sendRest(time);
		throw new ActionCommandException(StandardMessageURN.AK_REST);
	}
	
	/**
	 * 获取这个RCR Agent在某个特定周期发送的命令。
	 * 
	 * @param time
	 *            发送命令的时间
	 * @return 当前RCR Agent在某个特定周期发送的命令.
	 */
	public StandardMessageURN getCommandHistory(int time) {
		return commandHistory.get(time);
	}
	
	/**
	 * 获取当前RCR Agent的uniform。Uniform用于通信中，以减少发送的字节量。
	 * 
	 * @return 当前RCR Agent的uniform编号
	 */
	protected int getUniform() {
		return world.getUniform().toUniform(getID());
	}

	/**
	 * 判断当前RCR Agent能否看到某个entity。只需要判断这个entity是否在ChangeSet即可。
	 * 
	 * @param entity
	 *            需要判别的entity
	 * @return 当这个entity在ChangeSet内，返回true。否则，false。
	 */
	public boolean isVisible(StandardEntity entity) {
		return isVisible(entity.getID());
	}
	
	/**
	 * 判断当前RCR Agent能否看到某个entity。只需要判断这个entity是否在ChangeSet即可。
	 * 
	 * @param entity
	 *            需要判别的entity的id
	 * @return 当这个entity在ChangeSet内，返回true。否则，false。
	 */
	public boolean isVisible(EntityID id){
		return changed.getChangedEntities().contains(id);
	}
	
	/**
	 * 返回当前RCR Agent能看到的所有实体。
	 * 
	 * @return 当前RCR Agent能看到的所有实体。
	 */
	public Set<EntityID> getVisibleEntities() {
		if (changed != null) 
			return changed.getChangedEntities(); 
		return null;
	}

	public AdvancedWorldModel getModel() {
		return world;
	}

	public GroupingType getGroupingType() {
		return GroupingType.getGroupingType(me().getStandardURN());
	}
	
	public Random getRandom() {
		return random;
	}
	
	public Set<EntityID> getChanged() {
		return this.changed.getChangedEntities();
	}
	
	/**
	 * 判断一个platoon agent是否被路障围住。在这种状况下，platoon agent是不能移动的。
	 * 此处借用了服务器的判别方法。
	 * 
	 * @param human
	 *            目标agent
	 * @return 当目标agent被路障围住时，返回true。否则，false。
	 */
	public boolean isStucked(Human human) {
		Blockade blockade = isLocateInBlockade(human);
		if (blockade == null)
			return false;
		double minDistance = Ruler.getDistanceToBlock(blockade, human.getX(), human.getY());
		
		if (minDistance > 500){
			System.out.println(time + ", " + world.me + ", " + "is stucked");
			return true;
		}
		
		if (location() instanceof Building) {
			Building loc = (Building) location();
			
			Set<Road> entrances = world.getEntrance().getEntrance(loc);
			int size = entrances.size();
			int count = 0;
			for (Road next : world.getEntrance().getEntrance(loc)) {
				CSURoad road = world.getCsuRoad(next.getID());
				if (road.isNeedlessToClear())
					continue;
				count++;
			}
			
			if (count == size) 
				return true;
		}
		
		return false;
	}
	
	
	/**
	 * 判断一个platoon agent的xy坐标是否落在一个路障的多边形内
	 * 
	 * @return 当这个plaoon agent的xy坐标落在路障的多边形内返回true。否则，false。
	 */
	protected Blockade isLocateInBlockade(Human human) {
		int x = human.getX();
		int y = human.getY();
		for (EntityID entityID : changed.getChangedEntities()){
			StandardEntity se = world.getEntity(entityID);
			if (se instanceof Blockade){
				Blockade blockade = (Blockade) se;
				Shape s = blockade.getShape();
				if (s != null && s.contains(x, y)) {
					return blockade;
				}
			}
		}
		return null;
	}
	  
	/** 
	 * 判断一个platoon agent是否被路障挡住。这种情况下，agent不能按照原来的路径继续前进，但可以后退。
	 * using two judging methods: the located roads need to cleared; the position varies little.
	 * @return 当一个platoon agent被路障挡住时返回true。否则，false。
	 */
	public boolean isBlocked() {
		//？？
		if (time < world.getConfig().ignoreUntil + 2)
			return false;
		
		if (commandHistory.get(time - 1).equals(StandardMessageURN.AK_MOVE) &&
				commandHistory.get(time - 2).equals(StandardMessageURN.AK_MOVE)) {
			
			//201409
			EntityID position_id = this.positionHistory.get(time);
			StandardEntity position_entity = world.getEntity(position_id);
			if (position_entity instanceof Building) {
				
				Building position_building = (Building) position_entity;
				Set<Road> entranceList = world.getEntrance().getEntrance(position_building);
				for (Road entrance : entranceList) {
					CSURoad road = world.getCsuRoad(entrance.getID());
					if (road.isNeedlessToClear()) {
						return false;
					}
				}
				return true;
				
			}
			
			Pair<Integer, Integer> location_1 = locationHistory.get(time - 2);
			Pair<Integer, Integer> location_2 = locationHistory.get(time - 1);
			Pair<Integer, Integer> location_3 = locationHistory.get(time);
			
			if (location_1 == null || location_2 == null || location_3 == null)
				return false;
			
			double distance_1 = Ruler.getDistance(location_1, location_2);
			double distance_2 = Ruler.getDistance(location_2, location_3);
			
			if (distance_1 < 8000 && distance_2 < 8000) {
				return true;
			}
		}
		
		return false;
	}
	
    ///oak
	public boolean isHovering() {
		if (time < world.getConfig().ignoreUntil + 3)
			return false;
		if (commandHistory.get(time - 1).equals(StandardMessageURN.AK_MOVE) &&
				commandHistory.get(time - 2).equals(StandardMessageURN.AK_MOVE) &&
						commandHistory.get(time - 3).equals(StandardMessageURN.AK_MOVE)) { 
		  	EntityID positionA = this.positionHistory.get(time);
     		StandardEntity position_A = world.getEntity(positionA);
     		EntityID positionB = this.positionHistory.get(time-2);
     		StandardEntity position_B = world.getEntity(positionB);
     		if(position_A instanceof Road && position_B instanceof Road && positionA.equals(positionB))
     			return true;
		}
		return false;
	}
	
	@SuppressWarnings("serial")
	public static class ActionCommandException extends Exception {
		private StandardMessageURN message;

		public ActionCommandException(StandardMessageURN message) {
			super();
			this.message = message;
		}
	}
	
	/*public boolean failToMove() {
		if (time < world.getConfig().ignoreUntil + 5)
			return false;
		StandardMessageURN command_1 = commandHistory.get(time - 5);
		StandardMessageURN command_2 = commandHistory.get(time - 4);
		StandardMessageURN command_3 = commandHistory.get(time - 3);
		StandardMessageURN command_4 = commandHistory.get(time - 2);
		StandardMessageURN command_5 = commandHistory.get(time - 1);
		
		boolean flag_1 = command_1.equals(StandardMessageURN.AK_MOVE);
		boolean flag_2 = command_2.equals(StandardMessageURN.AK_MOVE);
		boolean flag_3 = command_3.equals(StandardMessageURN.AK_MOVE);
		boolean flag_4 = command_4.equals(StandardMessageURN.AK_MOVE);
		boolean flag_5 = command_5.equals(StandardMessageURN.AK_MOVE);
		
		EntityID location_1 = positionHistory.get(time - 5);
		EntityID location_2 = positionHistory.get(time - 4);
		EntityID location_3 = positionHistory.get(time - 3);
		EntityID location_4 = positionHistory.get(time - 2);
		EntityID location_5 = positionHistory.get(time - 1);
		
		boolean flag_6 = location_1.getValue() == location_2.getValue();
		boolean flag_7 = location_2.getValue() == location_3.getValue();
		boolean flag_8 = location_3.getValue() == location_4.getValue();
		boolean flag_9 = location_4.getValue() == location_5.getValue();
		
		if (flag_1 && flag_2 && flag_3 && flag_4 && flag_5)
			if (flag_6 && flag_7 && flag_8 && flag_9)
				return true;
		
		return false;
	}
	
	public AgentState getAgentState() {
		return this.agentState;
	}
	
	public void setAgentState(AgentState state) {
		world.getAgentStateMap().put(world.getTime(), state);
		this.agentState = state;
	}
	
	public List<Building> getBurningBuildingsFromChangeSet() {
		return getBurningBuildingsFromChangeSet(true, false);
	}

	public List<Building> getBurningBuildingsFromChangeSet(boolean sort, boolean noInfono) {
		List<Building> buildings;

		buildings = burningBuildingsInChangeSet;
		if (noInfono) {
			buildings = removeInfono(buildings);
		}
		if (sort) {
			Collections.sort(buildings, new DistanceComparator(location(), world));
		}
		return buildings;
	}

	public List<Building> removeInfono(List<Building> buildings) {
		List<Building> list;

		list = new ArrayList<Building>();
		for (Building building : buildings) {
			if (building.isFierynessDefined()) {
				list.add(building);
			} else {
				if (building.getFierynessEnum() != Fieryness.INFERNO) {
					list.add(building);
				}
			}
		}
		return list;
	}
	
	protected void processObserving(ChangeSet changed) {
		burningBuildingsInChangeSet.clear();
		for (EntityID next : changed.getChangedEntities()) {
			StandardEntity entity = world.getEntity(next);
			if (entity instanceof Building) {
				Building building = (Building) entity;
				if (building.isOnFire()) {
					burningBuildingsInChangeSet.add(building);
				}
			}
		}
	}
	public void assignRegions() {
		List<EntityID> team = world.getTeam();
		world.getRegionModel().assignRegionsToTeam();
		List<EntityRegion> regions = getAssignedRegions();

		for (EntityRegion region : regions) {
			getExploration().addEntitiesToExplore(region.getBuildings());
		}
	}

	private List<EntityRegion> getAssignedRegions() {
		if (world == null) {
			return null;
		} else {
			return getAssignedRegionGroup().getRegions();
		}
	}

	private RegionGroup getAssignedRegionGroup() {
		if (world == null) {
			return null;
		} else {
			return world.getRegionModel().getAssignedRegions(getID());
		}
	}

	public Exploration<Building> getExploration() {
		return world.getBuildingExploration();
	}

	public List<Road> getRegionRoads() {
		List<Road> entities;
		List<EntityRegion> regions;
		entities = new ArrayList<Road>();
		regions = getAssignedRegions();
		if (regions == null) {
			return null;
		}
		for (EntityRegion region : regions) {
			entities.addAll(region.getRoads());
		}
		return entities;
	}*/
}
