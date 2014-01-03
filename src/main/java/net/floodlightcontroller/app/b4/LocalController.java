package net.floodlightcontroller.app.b4;

import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;

import net.floodlightcontroller.app.b4.rmi.RemoteGlobalConstant;
import net.floodlightcontroller.app.b4.rmi.RemoteGlobalServer;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.devicemanager.IDeviceService;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListSet;

import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.Link;

import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalController extends ForwardingBase implements IOFMessageListener, IFloodlightModule, ILinkDiscoveryListener, IOFSwitchListener {

	protected IFloodlightProviderService floodlightProvider;
	protected ILinkDiscoveryService linkDiscoverer;
	protected IDeviceService deviceManager;
	
	
	protected ConcurrentSkipListSet<Long> macAddresses;
	protected static Logger logger;
	protected Map<String, String> configParams;
	
	protected boolean remote;
	RemoteGlobalServer server;
	
	protected Thread worker;
	
	protected LocalHandler handler;//a descriptor of self
		
	class workerThread implements Runnable {
		@Override
		public void run() {
			
		}		
	}

	@Override
	public String getName() {    
		return LocalController.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(ILinkDiscoveryService.class);
		l.add(IDeviceService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {    
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		floodlightProvider.addOFSwitchListener(this);
		linkDiscoverer = context.getServiceImpl(ILinkDiscoveryService.class);
		linkDiscoverer.addListener(this);
		deviceManager = context.getServiceImpl(IDeviceService.class);
		macAddresses = new ConcurrentSkipListSet<Long>();
		logger = LoggerFactory.getLogger(LocalController.class);
		configParams = context.getConfigParams(this);
		String global = configParams.get("global");
		logger.info("---------------remote:" + global);
		if(global != null && global.equals("enabled")) {
			remote = true;
		} else {
			remote = false;
		}
	}

	public LocalHandler getHandler() {
		return handler;
	}
	
	@Override
	public void startUp(FloodlightModuleContext context) {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		if(remote == true) {
			logger.info("====>connecting to global enabled!!");
			try {
				Registry registry = LocateRegistry.getRegistry("localhost", 
						RemoteGlobalConstant.RMI_PORT);
				server = (RemoteGlobalServer) registry.lookup(RemoteGlobalConstant.GLOBAL_ID);
				LocalHandler lhandler = server.contact();
				handler = lhandler;
				Registry localregistry = LocateRegistry.createRegistry(handler.portToUse);
				LocalRMIImpl impl = new LocalRMIImpl(this);
				localregistry.bind(handler.name, impl);
				logger.info("self-reigstered to global! Got id:" + handler.id);
			} catch (RemoteException e) {
				e.printStackTrace();
			} catch (NotBoundException e) {
				e.printStackTrace();
			} catch (AlreadyBoundException e) {
				e.printStackTrace();
			}
		} else {
			logger.info("====>connecting to global disabled!!");
		}
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		Ethernet eth =
				IFloodlightProviderService.bcStore.get(cntx,
						IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		Long sourceMACHash = Ethernet.toLong(eth.getSourceMACAddress());
		Long destMACHash = Ethernet.toLong(eth.getDestinationMACAddress());
		if (!macAddresses.contains(sourceMACHash)) {
			macAddresses.add(sourceMACHash);
			String macadd = HexString.toHexString(sourceMACHash);
			//CAUTION!!! SEND THIS INFO TO GLOBAL IMMIDIATELY UPON SEEING IT, OTHERWISE CONFUSTION ON GLOBAL WILL HAPPEN!
			//AND!!! EVEN THIS ASSUMES MININET HAS --MAC OPTION
			logger.info("MAC Address: {} seen on switch: {}",
					macadd,
					sw.getId());
			try {
				server.addHostSwitchMap(macadd, sw.getId());
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}	
		
		String destMAC = HexString.toHexString(destMACHash);		
		Long swid = null;
		try {
			swid = server.getSwitchByMac(destMAC);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
		if(swid == null) {
			logger.info("@@@@@@@@@@@@@@@dont know where is dest MAC:" + destMAC);
		} else {
			logger.info("@@@@@@@@@@@@@@@dest mac is on switch " + swid);
		}
		/*ConcurrentSkipListSet<Long> dpids = new ConcurrentSkipListSet<Long>(floodlightProvider.getAllSwitchDpids());
		String alldpids = "";
		for(Long dpid : dpids) {
			alldpids += dpid + " ";
		}
		logger.info("---------------alldpids:" + alldpids);
		logger.info("################################ of links:" + 
		linkDiscoverer.getLinks().size() + ":" + 
				linkDiscoverer.getSwitchLinks());*/
		return Command.CONTINUE;
	}

	@Override
	public void linkDiscoveryUpdate(LDUpdate update) {
		logger.info("++++++++++linkdiscoverupdate:" + update.getSrc() + ":" + update.getDst());
	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		logger.info("++++++++++----linkdiscoverupdate:" + updateList.get(0).getSrc() + ":" + updateList.get(0).getDst());
		for(LDUpdate update : updateList) {
			Link link = new Link(update.getSrc(), update.getSrcPort(), update.getDst(), update.getDstPort());
			try {
				server.addSwLink(link);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void switchAdded(long switchId) {		
		logger.info("++++++++++switch added:" + switchId);
	}

	@Override
	public void switchRemoved(long switchId) {		
		logger.info("++++++++++switch removed:" + switchId);		
	}

	@Override
	public void switchActivated(long switchId) {
		logger.info("++++++++++switch actived:" + switchId);		
	}

	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) {
		logger.info("++++++++++switch port changed:" + switchId);		
	}

	@Override
	public void switchChanged(long switchId) {
		logger.info("++++++++++switch changed:" + switchId);
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command processPacketInMessage(
			IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
			FloodlightContext cntx) {
		// TODO Auto-generated method stub
		return null;
	}

}