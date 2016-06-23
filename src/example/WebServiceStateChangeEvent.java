package example;

import java.util.EventObject;
import java.util.Vector;

public class WebServiceStateChangeEvent extends EventObject {

	private static final long serialVersionUID = 1L;
	
	private final Vector<Vector<Object>> data;
	private final int type;

	public WebServiceStateChangeEvent(final Object source, final Vector<Vector<Object>> newData, final int valueType) {
		super(source);
		this.data = newData;
		this.type = valueType;
	}

	public Vector<Vector<Object>> getData() {
		return data;
	}

	public int getType() {
		return type;
	}
}
