package org.rapla.plugin.dayresource.server;

import org.rapla.RaplaResources;
import org.rapla.components.calendarview.Block;
import org.rapla.components.calendarview.Builder;
import org.rapla.components.calendarview.CalendarView;
import org.rapla.components.calendarview.html.AbstractHTMLView;
import org.rapla.components.calendarview.html.HTMLWeekView;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.AppointmentFormater;
import org.rapla.entities.domain.permission.PermissionController;
import org.rapla.facade.ClientFacade;
import org.rapla.framework.RaplaException;
import org.rapla.framework.RaplaLocale;
import org.rapla.framework.logger.Logger;
import org.rapla.inject.Extension;
import org.rapla.plugin.abstractcalendar.AbstractRaplaBlock;
import org.rapla.plugin.abstractcalendar.GroupAllocatablesStrategy;
import org.rapla.plugin.abstractcalendar.RaplaBuilder;
import org.rapla.plugin.dayresource.DayResourcePlugin;
import org.rapla.plugin.weekview.server.HTMLDayViewPage;
import org.rapla.server.extensionpoints.HTMLViewPage;

import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Extension(provides = HTMLViewPage.class,id= DayResourcePlugin.DAY_RESOURCE_VIEW)
public class HTMLDayResourcePage extends HTMLDayViewPage
{
    @Inject
	public HTMLDayResourcePage(RaplaLocale raplaLocale, RaplaResources raplaResources, ClientFacade facade, Logger logger,
			AppointmentFormater appointmentFormater, PermissionController permissionController)
	{
		super(raplaLocale, raplaResources, facade, logger, appointmentFormater, permissionController);
	}

	@Override
	protected AbstractHTMLView createCalendarView() {
        HTMLWeekView weekView = new HTMLWeekView(){
            
            
        	@Override
        	protected String createColumnHeader(int i)
        	{
            	try 
            	{
					Allocatable allocatable = getSortedAllocatables().get(i);
					return  allocatable.getName( getRaplaLocale().getLocale());
				} 
            	catch (RaplaException e) {
					return "";
				}
        	}
            
            @Override
            protected int getColumnCount() {
            	try {
        		  Allocatable[] selectedAllocatables =model.getSelectedAllocatables();
        		  return selectedAllocatables.length;
          	  	} catch (RaplaException e) {
          	  		return 0;
          	  	}
            }
            
            public void rebuild(Builder b) {
                setWeeknumber(getRaplaLocale().formatDateShort(getStartDate()));
        		super.rebuild(b);
        	}
    		
        };
        return weekView;
    }
	
	 
	
	private int getIndex(final List<Allocatable> allocatables,
			Block block) {
		AbstractRaplaBlock b = (AbstractRaplaBlock)block;
		Allocatable a = b.getGroupAllocatable();
		int index = a != null ? allocatables.indexOf( a ) : -1;
		return index;
	}
	
	

	
	protected RaplaBuilder createBuilder() throws RaplaException {
        RaplaBuilder builder = super.createBuilder();

        final List<Allocatable> allocatables = getSortedAllocatables();
        builder.setSplitByAllocatables( true );
        builder.selectAllocatables(allocatables);
        GroupAllocatablesStrategy strategy = new GroupAllocatablesStrategy( getRaplaLocale().getLocale() )
        {
        	@Override
        	protected Map<Block, Integer> getBlockMap(CalendarView wv,
        			List<Block> blocks) 
        	{
        		if (allocatables != null)
        		{
        			Map<Block,Integer> map = new LinkedHashMap<Block, Integer>(); 
        			for (Block block:blocks)
        			{
        				int index = getIndex(allocatables, block);
        				
        				if ( index >= 0 )
        				{
        					map.put( block, index );
        				}
        		     }
        		     return map;		
        		}
        		else 
        		{
        			return super.getBlockMap(wv, blocks);
        		}
        	}

			
        };
       
        
        strategy.setResolveConflictsEnabled( true );
        builder.setBuildStrategy( strategy );

        return builder;
    }
	



}
