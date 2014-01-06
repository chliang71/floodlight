package net.floodlightcontroller.app.b4;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import net.floodlightcontroller.app.b4.rmi.RemoteGlobalServer;

public class GlobalRMIImpl extends UnicastRemoteObject implements RemoteGlobalServer {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	GlobalController controllerRef;
	
	protected GlobalRMIImpl(GlobalController con) throws RemoteException {
		super();
		controllerRef = con;
	}

	@Override
	public LocalHandler contact() throws RemoteException {
		return controllerRef.getNextHandler();
	}

	@Override
	public boolean addHostSwitchMap(String mac, Long swid) throws RemoteException {
		return controllerRef.addHostSwitchMap(mac, swid);
	}
	
	@Override
	public boolean addSwLink(Long src, Long dst) throws RemoteException {
		return controllerRef.addSwLink(src, dst);
	}

	@Override
	public Long getSwitchByMac(String mac) throws RemoteException {
		return controllerRef.getSwitchByMac(mac);
	}

	@Override
	public boolean addPortSwitchMap(String mac, Long swid)
			throws RemoteException {
		return controllerRef.addPortSwitchMap(mac, swid);
	}

}
