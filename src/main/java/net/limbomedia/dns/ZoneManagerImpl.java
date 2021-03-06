package net.limbomedia.dns;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.kuhlins.webkit.ex.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Address;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Zone;

import net.limbomedia.dns.model.UpdateResult;
import net.limbomedia.dns.model.XRecord;
import net.limbomedia.dns.model.XType;
import net.limbomedia.dns.model.XZone;

public class ZoneManagerImpl implements ZoneManager, ZoneProvider {
	
	private static final Logger L = LoggerFactory.getLogger(ZoneManagerImpl.class);
	
	private Persistence persistence;
	
	/**
	 * Internal zone model used for persistance and api. 
	 */
	private Collection<XZone> zones = new ArrayList<XZone>();
  
	/**
	 * External zone model used to answer requests via dnsjava.
	 */
	private Map<Name, Zone> resolveMap = new HashMap<Name, Zone>();
	
	public ZoneManagerImpl(Persistence persistence) throws IOException {
	  this.persistence = persistence;

		// Load zones initially
		zones = this.persistence.zonesLoad();
		updateResolveMap();
	}
	
  private void updateResolveMap() {
    Map<Name, Zone> result = new HashMap<Name, Zone>();

    for (XZone xzone : zones) {
      Zone z;
      Name nameZone;
      
      try {
        nameZone = new Name(xzone.getName());
        Name nameNameserver = new Name(xzone.getNameserver());
        
        // Auto-generate SOA and NS record
        SOARecord recordSOA = new SOARecord(nameZone, DClass.IN, 3600L,nameNameserver, new Name("hostmaster",nameZone),xzone.getSerial(), 21600L, 7200L, 2160000L, 3600L); // 6h, 2h, 25d, 1h 
        NSRecord recordNS = new NSRecord(nameZone, DClass.IN, 300L,nameNameserver);

        z = new Zone(nameZone, new Record[] {recordSOA,recordNS});
      } catch(IOException e) {
        L.warn("Cannot go live with zone {}. {}.", xzone.getName(), e.getMessage(), e);
        continue;
      }
      
      for(XRecord xrec : xzone.getRecords()) {
        try {
          Record r = null;
          if(XType.A.equals(xrec.getType())) {
            r = new ARecord(new Name(xrec.getName(),nameZone), DClass.IN, 300L, Address.getByAddress(xrec.getValue()));
          }
          else if(XType.AAAA.equals(xrec.getType())) {
            r = new AAAARecord(new Name(xrec.getName(),nameZone), DClass.IN, 300L, Address.getByAddress(xrec.getValue()));
          }
          else if(XType.CNAME.equals(xrec.getType())) {
            r = new CNAMERecord(new Name(xrec.getName(),nameZone), DClass.IN, 300L, new Name(xrec.getValue()));
          }
          
          if(r == null) {
            L.warn("Skipping a record in zone {}. Invalid/Unsupported: {}.", xzone.getName(), xrec);
            continue;
          }
          else {
            z.addRecord(r);
          }
        } catch(IOException e) {
          L.warn("Skipping a record in zone {}. {}.", xzone.getName(), e.getMessage(), e);
          continue;
        }
      }
      
      result.put(z.getOrigin(), z);
    }
    this.resolveMap = result;
	}
  
  private void onChange() {
    updateResolveMap();
    
    try {
      this.persistence.zonesSave(zones);
    } catch(IOException e) {
      L.error("Failed to write zone-configuration to file. {}.", e.getMessage(), e);
    }
  }
  
	private XZone getZone(String name) throws NotFoundException {
	  return zones.stream().filter(x -> x.getName().equals(name)).findAny().orElseThrow(() -> new NotFoundException(ErrorMsg.NOTFOUND_ZONE.name()));	  
	}

  private XRecord getRecord(String recordId) throws NotFoundException {
    return zones.stream().map(XZone::getRecords).flatMap(List::stream).filter(x -> x.getId().equals(recordId)).findAny().orElseThrow(() -> new NotFoundException(ErrorMsg.NOTFOUND_RECORD.name()));
  }

	private XRecord getRecordByToken(String token) throws NotFoundException {
    return zones.stream().map(XZone::getRecords).flatMap(List::stream).filter(x -> x.getToken() != null && x.getToken().equals(token)).findAny().orElseThrow(() -> new NotFoundException(ErrorMsg.NOTFOUND_RECORD.name()));
  }

	
  @Override
  public Zone zoneGet(Name name) {
    return resolveMap.get(name);
  }
  
	@Override
  public Collection<XZone> zoneGets() {
	  return zones;
	}
	
	@Override
  public XZone zoneGet(String zoneId) {
	  return getZone(zoneId);
	}
  
	@Override
	public synchronized XZone zoneCreate(String whoDidIt, XZone body) {
		Validator.validateZone(body, zones);

		XZone zone = new XZone();
		zone.setName(body.getName());
		zone.setNameserver(body.getNameserver());
		zones.add(zone);

		onChange();
		L.info("Zone created. By {}, Zone: {}.", whoDidIt, zone);
		return zone;
	}

	@Override
	public synchronized void zoneDelete(String whoDidIt, String zoneId) {
	  XZone zone = getZone(zoneId);
		zones.remove(zone);
    onChange();
    L.info("Zone deleted. By: {}, Zone: {}.", whoDidIt, zone);
	}

  @Override
  public synchronized XRecord recordGet(String zoneId, String recordId) {
    return getRecord(recordId);
  }

	@Override
	public synchronized XRecord recordCreate(String whoDidIt, String zoneId, XRecord body) {
    XZone zone = getZone(zoneId);

		Validator.validateRecordCreate(body, zone, zones);

		XRecord record = new XRecord();
		record.setId(UUID.randomUUID().toString());
		record.setName(body.getName());
		record.setType(body.getType());
		record.setValue(body.getValue());
		record.setLastChange(new Date());
		record.setToken(body.getToken());
  
		zone.addRecord(record);
		zone.incrementSerial();
    
		onChange();
		L.info("Record updated. By: {}, Zone: {}, {}.", whoDidIt, zone.getName(), record);
		return record;
	}
	
	@Override
  public synchronized void recordDelete(String whoDidIt, String zoneId, String recordId) {
		XRecord record = getRecord(recordId);
    
		XZone zone = record.getZone();
		
		zone.incrementSerial();
		zone.removeRecord(record);
    
		onChange();
		L.info("Record deleted. By: {}, Zone: {}, {}.", whoDidIt, zone.getName(), record);		
	}

	@Override
	public synchronized XRecord recordUpdate(String whoDidIt, String zoneId, String recordId, XRecord body) {
    XRecord record = getRecord(recordId);

	  Validator.validateRecordUpdate(body, record, zones);
    
		// Save changes, increment zone-serial, store as file and update zones.
    record.setToken(body.getToken());
    if(!record.getValue().equals(body.getValue())) {
      record.setValue(body.getValue());
		  record.setLastChange(new Date());
  	}
    
    record.getZone().incrementSerial();

		onChange();
		L.info("Record updated. By: {}, Zone: {}, {}.", whoDidIt, record.getZone().getName(), record);
		return record;
	}
	
	@Override
  public UpdateResult recordDynDNS(String whoDidIt, String recordToken, String value) {
    XRecord record = getRecordByToken(recordToken);
    
    Validator.validateRecordValue(record.getType(), value);
    
    Date now = new Date();
    record.setLastUpdate(now);
    
    UpdateResult result;
    
    if(record.getValue().equals(value)) {
      result = new UpdateResult(false, value);
    }
    else {
      record.setValue(value);
      record.setLastChange(now);
      result = new UpdateResult(true, value);
      onChange();
    }
    
    L.info("Value updated ({}). By: {}, Zone: {}, {}.", result.isChanged() ? "change" : "same", whoDidIt, record.getZone().getName(), record);
    return result;
  }
	
	
}
