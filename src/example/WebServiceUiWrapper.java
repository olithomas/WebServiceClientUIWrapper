package example;

import java.io.IOException;
import java.io.StringReader;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.xml.rpc.ServiceException;
import javax.xml.rpc.holders.IntHolder;
import javax.xml.rpc.holders.StringHolder;
import javax.swing.SwingWorker;
import javax.xml.parsers.*;

import org.apache.axis.types.UnsignedInt;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * This is an example implementation of a Web Service Client wrapper for use in GUI applications. The original
 * implementation was for a specific application and Web Service which I am unable to share on GitHub (it was a work thing)
 * , hence I have tried to generalise it into this example.
 * More specifically, the class maintains an instance of a Java Web Service Client stub, of the type that is auto-generated
 * by JAX-RPC tools.
 * Usually, these auto-generated classes will provide a stub implementation for the web service based on the WSDL
 * document. The stub class will provide methods for accessing the web service calls. These methods will take container
 * types as arguments and can often block for relatively long amounts of time. This makes the calls unsuitable for direct
 * use within a GUI application developed using Swing.
 * In addition, some web services will provide state information for the web service, but this state information is not
 * updated automatically upon changes, and no mechanism is provided for storage of this state.
 * So this class has been developed in order to provide the following advantages to the developer of a Swing application:
 * - A set of variables for the storing of relevant state information of the web service;
 * - Ability to register Listeners and fire Custom Events for changes to the state of the web service;
 * - A worker thread for the regular updating of relevant state (and triggering of state change events);
 * - A single synchronised method for accessing the web service calls, using HashMaps for input and output.
 * - A static method for parsing the XML responses from the web service.
 * The state is stored in the form of Vectors of Vectors, which has been chosen so the state information can be easily 
 * manipulated in the Swing JTable class.
 * The web service call API itself has been reduced down to a single method with a common inputDataMap and outputDataMap.
 * This allows there to be a single SwingWorker implementation for use in the GUI, and simplifies the overall design.
 * The input and output data maps are of type <String, Object> where the String is the name of the parameter (stored as
 * constants in this implementation) and the Object is the value of the parameter. Cast will have to be done by the user
 * of the implementation to get to the various data types but due to the static nature of the WSDL, this is relatively safe.
 * @author LTE5
 *
 */
public class WebServiceUiWrapper {
	
	/*
	 * Any state information may be held in Vectors (as these work nicely with Swing JTables).
	 * It's more efficient to initialise the size of vectors when you create them..
	 */
	private static final int INITIAL_VECTOR_SIZE = 1001;
	
	/*
	 * Constants that represent different method calls to the web service, including a special constant for a disconnect event
	 */
	public static final int WS_DISCONNECT = -1;
	public static final int WS_OPERATION_0 = 0;
	public static final int WS_OPERATION_1 = 1;
	public static final int WS_OPERATION_2 = 2;
	public static final int WS_OPERATION_3 = 3;
	public static final int WS_OPERATION_4 = 4;
	
	/*
	 * These are the names of the parameters passed in and out of the web service methods.
	 * They are specified statically here and used to create the inputDataMap and outputDataMap in the
	 * getBlockingWebServiceCall() method.
	 * Obviously these are specific to actual web service definition being deployed. Below are some example names
	 */
	public static final String S_STRING_VAL_1 = "sStringVal1";
	public static final String S_STRING_VAL_2 = "sStringVal2";
	public static final String S_STRING_VAL_3 = "sStringVal3";
	public static final String S_STRING_VAL_4 = "sStringVal4";
	public static final String N_INT_VAL_1 = "nIntVal1";
	public static final String N_INT_VAL_2 = "nIntVal2";
	public static final String N_INT_VAL_3 = "nIntVal3";
	public static final String N_INT_VAL_4 = "nIntVal4";
	
	/*
	 * Global setting for the refresh rate of the state worker thread.
	 */
	public static final long WORKER_REFRESH_RATE = 5000L;
	
	/*
	 * XML Tag names used to identify information returned by the web server.
	 * Add any commonly used tags here
	 */
	private static final String XML_TAG_NAME_1 = "XmlTag1";
	private static final String XML_TAG_NAME_2 = "XmlTag2";
	
	/*
	 * ipAddr is for convenience access.
	 */
	private final String ipAddr;
	
	/*
	 * Fields for managing the worker thread
	 */
	private ExecutorService workerService;
	private Runnable worker;
	private Future<?> workerFuture = null;
	private List<WebServiceStateChangeListener> listeners;

	/*
	 * This is where the stub server class that is generated by the JAX-RS Web Service Client generator or similar, 
	 * would be.. (the actual WebServiceStub class referenced is a simple stub to make the example work)
	 */
	private WebServiceStub webServiceStub;
	
	/**
	 * Initialise the Web Service Interface to the specified IP address. 
	 * The constructor will block while it tests the connection, and will throw a RemoteException
	 * if the IP address is unreachable, which could take quite some time!
	 * The construction of the object must therefore be carried out in a SwingWorker or other worker thread
	 * to prevent the GUI hanging.
	 * @throws RemoteException if the created webServiceStub is unreachable (or some other remote error)
	 */
	public WebServiceUiWrapper(String ipAddr) throws RemoteException {
		String wsdlUrl = "http://" + ipAddr + "?wsdl";
		
		webServiceStub = new WebServiceStub(wsdlUrl);
		
		/*
		 * Test the connection before we go any further. 
		 * This call with block and throw a RemoteException if the webServiceStub is unreachable.
		 */
		webServiceStub.failIfUnreachable();
		
		// Connection was successful, so carry on initialisation..
		this.listeners = new ArrayList<WebServiceStateChangeListener>();
		this.ipAddr = ipAddr;
		// initialise worker fields
		this.workerService = Executors.newSingleThreadExecutor();
		this.worker = new WorkerThread();
	}
	
	/**
	 * Starts, or re-starts, the worker thread. 
	 * The values of the tables will be updated by the worker thread via synchronised setter 
	 * methods in this class.
	 * These values may then be accessed by other threads by means of synchronised get methods.
	 */
	public void initStateWorker() {
		if (workerFuture == null) {
			workerFuture = workerService.submit(worker);
		} else if (workerFuture.isDone()) {
			workerFuture = null;
			workerFuture = workerService.submit(worker);
		} else {
			// worker is already running so no action taken
		}
	}
	
	/**
	 * Attempts to cancel the worker thread execution, if running.
	 * NOTE: the behaviour is the same regardless of what the call to Future.cancel() returns
	 * The worker thread may be started again by means of calling initStateWorker()
	 */
	public void stopStateWorker() {
		if (((WorkerThread) worker).isRunning()) {
			workerFuture.cancel(true);
		} else {
		}
	}
	
	/**
	 * Add a WebServiceStateChangeListener to this objects list of WebServiceStateChangeListeners
	 * @param l - the listener to add
	 */
	public void addWebServiceStateChangeListener(WebServiceStateChangeListener l) {
		this.listeners.add(l);
	}

	/**
	 * fires an event containing new data to all registered listeners. The event can be new data for one of the
	 * tables, or a WS_DISCONNECT event in which case newData is null.
	 * @param newData - The new data that caused the event, or null if this is a WS_DISCONNECT event
	 * @param callType - The type of event, which can be one of the defined constants for new data events
	 * or WS_DISCONNECT for a diconnected event
	 */
	private void fireWebServiceStateChangeEvent(Vector<Vector<Object>> newData, int callType) {
		WebServiceStateChangeEvent event = new WebServiceStateChangeEvent(this, newData, callType);
		for (WebServiceStateChangeListener l : listeners) {
			l.webServiceStateChanged(event);
		}
	}
	
	/**
	 * <p>
	 * Executes the blocking Web Service call specified by callType. This method will block until the web service
	 * returns and so this would normally be executed from within a SwingWorker when being called by the GUI. It is
	 * also called from the worker thread however, and so must be synchronised (as I have to assume the auto-generated
	 * Web Service implementation is not thread-safe).
	 * </p>
	 * <p>
	 * This method hides the complexity of the stub implementation and allows a single general purpose SwingWorker
	 * to be implemented that passes in and accepts back a HashMap of the input and output parameters.
	 * The input and output parameters are identified by a key of type String set to the name of the parameter as 
	 * specified in the WSDL document ('nErrorCode' for example).
	 * </p>
	 * <p>
	 * For input parameters: the type of the Object passed in as the value must be that specified in the API reference
	 * for the Web Service. Currently this is just 'Integer' and 'String' and 'XML' (XML to be defined).
	 * For example: parameter 'nTestRunId' has a type of 'Integer' and so an Integer or int must be passed in the Map.
	 * </p>
	 * @param inputDataMap - A HashMap of the input parameters specified by the WSDL document for the call
	 * @param callType - A constant specifying the type of call to execute
	 * @return A HashMap of the output parameters specified by the WSDL document for the call.
	 */
	public synchronized HashMap<String, Object> getBlockingWebServiceCall(final HashMap<String, Object> inputDataMap, int callType)
		throws RemoteException { // Returns: outputDataMap
		
		// Map for output parameters
		HashMap<String, Object> outputDataMap = new HashMap<String, Object>();
		
		switch(callType) {
		case WS_OPERATION_0:
			// Get the input parameters:
			UnsignedInt nIntVal1 = new UnsignedInt(getUnsignedLong((Integer)inputDataMap.get(N_INT_VAL_1)));
			// Initialise the call-specific output parameter holders:
			IntHolder nIntVal2 = new IntHolder();
			StringHolder sStringVal1 = new StringHolder();
			// Make the call:
			webServiceStub.operation0(nIntVal1, nIntVal2, sStringVal1);
			// Populate the output Map:
			outputDataMap.put(N_INT_VAL_2, nIntVal2.value);
			outputDataMap.put(S_STRING_VAL_1, sStringVal1.value);
			break;
		case WS_OPERATION_1:
			// Add in the logic for all the other blocking web service calls here...
			break;
		case WS_OPERATION_2:
			// Add in the logic for all the other blocking web service calls here...
			break;
		case WS_OPERATION_3:
			// Add in the logic for all the other blocking web service calls here...
			break;
		case WS_OPERATION_4:
			// Add in the logic for all the other blocking web service calls here...
			break;
		}
		return outputDataMap;
	}
	
	/**
	 * Parses the passed in XML and returns a populated Vector of Vectors with either a list of tag1 node values or
	 * a list of tag2 node values, which ever is detected within the XML
	 * @param xml - The XML to parse
	 * @return a Vector of Vectors of type Object containing the information parsed from the XML
	 * @throws IOException if the parser returns a SAXException because of a parse error 
	 * (we don't want to deal with SAXExceptions so we throw a new IOException)
	 */
	private static Vector<Vector<Object>> parseResponseXml(String xml) throws IOException {

		Vector<Vector<Object>> outputTable = new Vector<Vector<Object>>(INITIAL_VECTOR_SIZE);
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder;
		try {
			builder = factory.newDocumentBuilder();
			InputSource is = new InputSource(new StringReader(xml));
			Document doc;
			doc = builder.parse(is);
			// check for interesting tags:
			NodeList tag1Nodes = doc.getElementsByTagName(XML_TAG_NAME_1);
			NodeList tag2Nodes = doc.getElementsByTagName(XML_TAG_NAME_2);
			if (tag1Nodes.getLength() > 0) { // then the XML contained tag1 nodes
				for (int i = 0; i < tag1Nodes.getLength(); i++) {
					NamedNodeMap tag1NodeAttrib = tag1Nodes.item(i).getAttributes();
					int tag1NodeAttribLen = tag1NodeAttrib.getLength();
					Vector<Object> tag1Row = new Vector<Object>(tag1NodeAttribLen);
					
					for (int a = 0; a < tag1NodeAttribLen; a++) {
						tag1Row.addElement(tag1NodeAttrib.item(a).getNodeValue());
					}
					outputTable.addElement(tag1Row);
				}
			} else if (tag2Nodes.getLength() > 0) { // then the XML contained tag2 nodes
				// Do the same stuff as for tag1 nodes above
			} else { // No tag1 or tag2 tags found, so throw an exception
				throw new IOException("No 'tag1' or 'tag2' elements found in XML.");
			}
		} catch (ParserConfigurationException ignore) { // factory.newDocumentBuilder()
			// We made the factory with the DocumentBuilderFactory, so ignore any exception
			
		} catch (SAXException e) { // builder.parse(is)
			// If the parser can't parse it throws a SAXException.
			// we'll throw a new IOException instead
			throw new IOException("Some problem parsing the XML (SAXException): " + e.getMessage());

		} catch (IOException ignore) { // builder.parse(is)
			// where there is an IO error because of 'is'. The 'is' is made by us so ignore this exception

		}

		// outputTable can contain stateTable1 or test runs. It's up to the caller to know which!!
		return outputTable;
	}

	/**
	 * Get an instance of SwingWorker that will execute the passed in web service call. get() can be called on
	 * the SwingWorker later, when an event is generated to tell the listener it is DONE.
	 * @param inputDataMap
	 * @param callType
	 * @return
	 */
	public SwingWorker<HashMap<String, Object>, String> getBlockingWebServiceCallFuture(
			HashMap<String, Object> inputParams,
			int callType) {
		return new BlockingWebServiceCallFuture(inputParams, callType);
	}
	
	/**
	 * Get the ip address of the web service
	 * 
	 * @return the IP address of the web service
	 */
	public String getIpAddr() {
		return this.ipAddr;
	}
	
	private static long getUnsignedLong(int x) {
	    return x & 0x00000000ffffffffL;
	}
	
	private class WorkerThread implements Runnable {
		
		private volatile boolean isRunning;
		/*
		 * Object vectors for holding the data state for the web service
		 * Actual web service may have more or less state variables to keep track of
		 */
		private Vector<Vector<Object>> stateTable1;
		private Vector<Vector<Object>> stateTable2;
		private Vector<Vector<Object>> stateTable3;
		
		public WorkerThread() {
			this.isRunning = false;
			this.stateTable1 = new Vector<Vector<Object>>(INITIAL_VECTOR_SIZE);
			this.stateTable3 = new Vector<Vector<Object>>(INITIAL_VECTOR_SIZE);
			this.stateTable2 = new Vector<Vector<Object>>(INITIAL_VECTOR_SIZE);
		}

		@Override
		public void run() {
			isRunning = true;
			try {
				while (true) {
					// Check we haven't been interrupted first
					if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
					
					// Get the new data for each of the state tables:
					Vector<Vector<Object>> newValsTable1 = getTableData(WS_OPERATION_2);
					Vector<Vector<Object>> newValsTable2 = getTableData(WS_OPERATION_3);
					Vector<Vector<Object>> newValsTable3 = getTableData(WS_OPERATION_4);

					// If the new data is changed, then fire the relevant event and update the state variable:
					if (!this.stateTable1.equals(newValsTable1)) {
						fireWebServiceStateChangeEvent(newValsTable1, WS_OPERATION_2);
						this.stateTable1.clear();
						this.stateTable1.addAll(newValsTable1);
					}
					if (!this.stateTable2.equals(newValsTable2)) {
						fireWebServiceStateChangeEvent(newValsTable2, WS_OPERATION_3);
						this.stateTable2.clear();
						this.stateTable2.addAll(newValsTable2);
					}
					if (!this.stateTable3.equals(newValsTable3)) {
						fireWebServiceStateChangeEvent(newValsTable3, WS_OPERATION_4);
						this.stateTable3.clear();
						this.stateTable3.addAll(newValsTable3);
					}

					// And now go back to sleep again for refresh time
					Thread.sleep(WebServiceUiWrapper.WORKER_REFRESH_RATE);
				}
			} catch (InterruptedException ignore) {
				// We got interrupted...
			} catch (RemoteException e) {
				// Some remote error returned by the Web Service, fire a WS_DISCONNECT event...
				fireWebServiceStateChangeEvent(null, WS_DISCONNECT);
			} catch (Exception e) {
				// Worker thread - unknown Exception
				e.printStackTrace();
			} finally {
				isRunning = false;
			}
			
		}
		
		protected boolean isRunning() {
			return isRunning;
		}
		
		/**
		 * <p>Get new data from the web service, according to the passed in call type.
		 * </p>
		 * @return a Vector of Vectors of type Object containing the return information.
		 * @throws RemoteException if the stub has become unreachable or some other remote error is returned
		 */
		private Vector<Vector<Object>> getTableData(int callType) throws RemoteException {
			
			HashMap<String, Object> inputDataMap = new HashMap<String, Object>();
			HashMap<String, Object> outputDataMap;
			outputDataMap = getBlockingWebServiceCall(inputDataMap, callType);
			// If no errors returned then the response is valid.
			// Assuming the response is XML, parse and return it:
			String outputString = (String) outputDataMap.get(S_STRING_VAL_2);
			Vector<Vector<Object>> newVals;
			try {
				newVals = WebServiceUiWrapper.parseResponseXml(outputString);
				return newVals;
			} catch (IOException e) {
				throw new RemoteException("XML Response Value was badly formed.", e);
			}
		}
	}
	
	/**
	 * An implementation of the SwingWorker class for running of blocking web service calls.
	 * The return type of the SwingWorker is a HashMap which will contain the outputDataMap from the web service call
	 * when the user calls get() on the instance.
	 * @author LTE5
	 *
	 */
	public final class BlockingWebServiceCallFuture extends SwingWorker<HashMap<String, Object>, String> {
		
		private final int callType;
		private final HashMap<String, Object> inputDataMap;

		public BlockingWebServiceCallFuture(final HashMap<String, Object> inputDataMap, final int callType) {
			this.inputDataMap = inputDataMap;
			this.callType = callType;
		}

		@Override
		protected HashMap<String, Object> doInBackground() throws Exception {
			try {
				setProgress(1);
				HashMap<String, Object> outputDataMap = getBlockingWebServiceCall(inputDataMap, callType);
				setProgress(100);
				return outputDataMap;
			} catch (Exception e) {
				throw e;
			}
		}
	}
}