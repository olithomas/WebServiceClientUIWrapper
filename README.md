JAX-RPC Web Service Client UI Wrapper
=====================================
A wrapper to make usage of auto-generated JAX-RPC Web Service Clients from with GUI application easier.
General Description:
--------------------
This is an example implementation of a JAX-RPC Web Service Client wrapper for use in GUI applications. The original implementation was for a specific application and Web Service which I am unable to share on GitHub (it was a work thing), hence I have tried to generalise it into this example.

More specifically, the class maintains an instance of a Java Web Service Client stub, of the type that is auto-generated by JAX-RPC tools.

Usually, these auto-generated classes will provide a stub implementation for the web service based on the WSDL document. The stub class will provide methods for accessing the web service calls. These methods will take RPC container types as arguments and can often block for relatively long amounts of time. This makes the calls unsuitable for direct use within a GUI application developed using Swing.

In addition, some web services will provide state information for the web service, but this state information is not updated automatically upon changes, and no mechanism is provided for storage of this state.

So this class has been developed in order to provide the following advantages to the developer of a Swing application:

* A set of variables for the storing of relevant state information of the web service;
* Ability to register Listeners and fire Custom Events for changes to the state of the web service;
* A worker thread for the regular updating of relevant state (and triggering of state change events);
* A single synchronised method for accessing the web service calls, using HashMaps for input and output.
* A static method for parsing the XML responses from the web service.

The state is stored in the form of Vectors of Vectors, which has been chosen so the state information can be easily  manipulated in the Swing JTable class. 

The web service call API itself has been reduced down to a single method with a common inputDataMap and outputDataMap. This allows there to be a single SwingWorker implementation for use in the GUI, and simplifies the overall design. The input and output data maps are of type <String, Object> where the String is the name of the parameter (stored as constants in this implementation) and the Object is the value of the parameter. Cast will have to be done by the user of the implementation to get to the various data types but due to the static nature of the WSDL, this is relatively safe.
