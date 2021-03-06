package net.osmand.swing;

import java.awt.Graphics2D;

public interface MapPanelLayer {
	
	public void initLayer(MapPanel map);
	
	public void destroyLayer();
	
	public void prepareToDraw();
	
	public void paintLayer(Graphics2D g);
	
}
