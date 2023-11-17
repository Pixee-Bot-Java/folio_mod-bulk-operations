package org.folio.bulkops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.ObjectUtils;
import org.folio.bulkops.client.CallNumberTypeClient;
import org.folio.bulkops.client.HoldingsClient;
import org.folio.bulkops.client.HoldingsNoteTypeClient;
import org.folio.bulkops.client.HoldingsSourceClient;
import org.folio.bulkops.client.HoldingsTypeClient;
import org.folio.bulkops.client.IllPolicyClient;
import org.folio.bulkops.client.LocationClient;
import org.folio.bulkops.client.StatisticalCodeClient;
import org.folio.bulkops.domain.bean.HoldingsNoteType;
import org.folio.bulkops.domain.bean.HoldingsRecord;
import org.folio.bulkops.domain.bean.HoldingsRecordsSource;
import org.folio.bulkops.domain.bean.HoldingsType;
import org.folio.bulkops.domain.bean.IllPolicy;
import org.folio.bulkops.domain.bean.ItemLocation;
import org.folio.bulkops.domain.bean.StatisticalCode;
import org.folio.bulkops.exception.NotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.folio.bulkops.util.Constants.QUERY_ALL_RECORDS;
import static org.folio.bulkops.util.Constants.QUERY_PATTERN_NAME;
import static org.folio.bulkops.util.Utils.encode;

@Service
@RequiredArgsConstructor
@Log4j2
public class HoldingsReferenceService {

  private final HoldingsClient holdingsClient;
  private final HoldingsTypeClient holdingsTypeClient;
  private final LocationClient locationClient;
  private final CallNumberTypeClient callNumberTypeClient;
  private final HoldingsNoteTypeClient holdingsNoteTypeClient;
  private final IllPolicyClient illPolicyClient;
  private final HoldingsSourceClient holdingsSourceClient;
  private final StatisticalCodeClient statisticalCodeClient;

  @Cacheable(cacheNames = "holdings")
  public HoldingsRecord getHoldingsRecordById(String id) {
    return holdingsClient.getHoldingById(id);
  }

  @Cacheable(cacheNames = "holdingsTypesNames")
  public HoldingsType getHoldingsTypeById(String id) {
    try {
      return holdingsTypeClient.getById(id);
    } catch (Exception e) {
      throw new NotFoundException(format("Holdings type not found by id=%s", id));
    }
  }

  @Cacheable(cacheNames = "holdingsTypeIds")
  public HoldingsType getHoldingsTypeByName(String name) {
    var holdingsTypes = holdingsTypeClient.getByQuery(format(QUERY_PATTERN_NAME, encode(name)));
    if (holdingsTypes.getHoldingsTypes().isEmpty()) {
      throw new NotFoundException(format("Holdings type not found by name=%s", name));
    }
    return holdingsTypes.getHoldingsTypes().get(0);
  }

  @Cacheable(cacheNames = "holdingsLocationsNames")
  public ItemLocation getLocationById(String id) {
    try {
      return locationClient.getLocationById(id);
    } catch (Exception e) {
      throw new NotFoundException(format("Location not found by id=%s", id));
    }
  }

  public ItemLocation getLocationIdByName(String name) {
    var locations = locationClient.getByQuery(format(QUERY_PATTERN_NAME, encode(name)));
    if (locations.getLocations().isEmpty()) {
      throw new NotFoundException(format("Location not found by name=%s", name));
    }
    return locations.getLocations().get(0);
  }

  @Cacheable(cacheNames = "holdingsCallNumberTypesNames")
  public String getCallNumberTypeNameById(String id) {
    try {
      return  callNumberTypeClient.getById(id).getName();
    } catch (Exception e) {
      throw new NotFoundException(format("Call number type not found by id=%s", id));
    }
  }

  @Cacheable(cacheNames = "holdingsCallNumberTypes")
  public String getCallNumberTypeIdByName(String name) {
    var callNumberTypes = callNumberTypeClient.getByQuery(format(QUERY_PATTERN_NAME, encode(name)));
    if (callNumberTypes.getCallNumberTypes().isEmpty()) {
      throw new NotFoundException(format("Call number type not found by name=%s", name));
    }
    return callNumberTypes.getCallNumberTypes().get(0).getId();
  }

  @Cacheable(cacheNames = "holdingsNoteTypesNames")
  public String getNoteTypeNameById(String id) {
    try {
      return holdingsNoteTypeClient.getById(id).getName();
    } catch (Exception e) {
      throw new NotFoundException(format("Note type not found by id=%s", id));
    }
  }

  @Cacheable(cacheNames = "holdingsNoteTypes")
  public String getNoteTypeIdByName(String name) {
    var noteTypes = holdingsNoteTypeClient.getByQuery(format(QUERY_PATTERN_NAME, encode(name)));
    if (noteTypes.getHoldingsNoteTypes().isEmpty()) {
      throw new NotFoundException(format("Note type not found by name=%s", name));
    }
    return noteTypes.getHoldingsNoteTypes().get(0).getId();
  }

  @Cacheable(cacheNames = "illPolicyNames")
  public IllPolicy getIllPolicyById(String id) {
    try {
      return illPolicyClient.getById(id);
    } catch (Exception e) {
      throw new NotFoundException(format("Ill policy not found by id=%s", id));
    }
  }

  @Cacheable(cacheNames = "illPolicies")
  public IllPolicy getIllPolicyByName(String name) {
    if (isEmpty(name)) {
      return null;
    }
    var illPolicies = illPolicyClient.getByQuery(format(QUERY_PATTERN_NAME, encode(name)));
    if (illPolicies.getIllPolicies().isEmpty()) {
      throw new NotFoundException(format("Ill policy not found by name=%s", name));
    }
    return illPolicies.getIllPolicies().get(0);
  }

  @Cacheable(cacheNames = "holdingsSourceNames")
  public HoldingsRecordsSource getSourceById(String id) {
    try {
      return holdingsSourceClient.getById(id);
    } catch (Exception e) {
      throw new NotFoundException(format("Holdings record source not found by id=%s", id));
    }
  }

  @Cacheable(cacheNames = "holdingsSources")
  public HoldingsRecordsSource getSourceByName(String name) {
    var sources = holdingsSourceClient.getByQuery(format(QUERY_PATTERN_NAME, encode(name)));
    if (ObjectUtils.isEmpty(sources) || ObjectUtils.isEmpty(sources.getHoldingsRecordsSources())) {
      throw new NotFoundException(format("Source not found by name=%s", name));
    }
    return sources.getHoldingsRecordsSources().get(0);
  }

  @Cacheable(cacheNames = "holdingsStatisticalCodeNames")
  public StatisticalCode getStatisticalCodeById(String id) {
    try {
      return statisticalCodeClient.getById(id);
    } catch (Exception e) {
      throw new NotFoundException(format("Statistical code not found by id=%s", id));
    }
  }

  @Cacheable(cacheNames = "holdingsStatisticalCodes")
  public StatisticalCode getStatisticalCodeByName(String name) {
    var statisticalCodes = statisticalCodeClient.getByQuery(format(QUERY_PATTERN_NAME, encode(name)));
    if (statisticalCodes.getStatisticalCodes().isEmpty()) {
      throw new NotFoundException(format("Statistical code not found by name=%s", name));
    }
    return statisticalCodes.getStatisticalCodes().get(0);
  }

  @Cacheable(cacheNames = "holdingsNoteTypes")
  public List<HoldingsNoteType> getAllHoldingsNoteTypes() {
    return holdingsNoteTypeClient.getByQuery(QUERY_ALL_RECORDS).getHoldingsNoteTypes();
  }
}
