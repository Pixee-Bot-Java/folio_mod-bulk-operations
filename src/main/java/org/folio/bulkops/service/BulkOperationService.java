package org.folio.bulkops.service;

import static java.lang.String.format;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.folio.bulkops.domain.dto.ApproachType.IN_APP;
import static org.folio.bulkops.domain.dto.ApproachType.MANUAL;
import static org.folio.bulkops.domain.dto.ApproachType.QUERY;
import static org.folio.bulkops.domain.dto.BulkOperationStep.UPLOAD;
import static org.folio.bulkops.domain.dto.OperationStatusType.APPLY_CHANGES;
import static org.folio.bulkops.domain.dto.OperationStatusType.COMPLETED;
import static org.folio.bulkops.domain.dto.OperationStatusType.COMPLETED_WITH_ERRORS;
import static org.folio.bulkops.domain.dto.OperationStatusType.DATA_MODIFICATION;
import static org.folio.bulkops.domain.dto.OperationStatusType.EXECUTING_QUERY;
import static org.folio.bulkops.domain.dto.OperationStatusType.FAILED;
import static org.folio.bulkops.domain.dto.OperationStatusType.NEW;
import static org.folio.bulkops.domain.dto.OperationStatusType.RETRIEVING_RECORDS;
import static org.folio.bulkops.domain.dto.OperationStatusType.REVIEW_CHANGES;
import static org.folio.bulkops.domain.dto.OperationStatusType.SAVED_IDENTIFIERS;
import static org.folio.bulkops.domain.dto.OperationStatusType.SAVING_RECORDS_LOCALLY;
import static org.folio.bulkops.util.Constants.FIELD_ERROR_MESSAGE_PATTERN;
import static org.folio.bulkops.util.Utils.resolveEntityClass;
import static org.folio.spring.scope.FolioExecutionScopeExecutionContextManager.getRunnableWithCurrentFolioContext;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.folio.bulkops.client.BulkEditClient;
import org.folio.bulkops.client.DataExportSpringClient;
import org.folio.bulkops.client.RemoteFileSystemClient;
import org.folio.bulkops.domain.bean.BulkOperationsEntity;
import org.folio.bulkops.domain.bean.ExportType;
import org.folio.bulkops.domain.bean.ExportTypeSpecificParameters;
import org.folio.bulkops.domain.bean.HoldingsRecord;
import org.folio.bulkops.domain.bean.Job;
import org.folio.bulkops.domain.bean.JobStatus;
import org.folio.bulkops.domain.bean.StatusType;
import org.folio.bulkops.domain.converter.BulkOperationsEntityCsvWriter;
import org.folio.bulkops.domain.dto.ApproachType;
import org.folio.bulkops.domain.dto.BulkOperationRuleCollection;
import org.folio.bulkops.domain.dto.BulkOperationStart;
import org.folio.bulkops.domain.dto.BulkOperationStep;
import org.folio.bulkops.domain.dto.EntityType;
import org.folio.bulkops.domain.dto.IdentifierType;
import org.folio.bulkops.domain.dto.OperationStatusType;
import org.folio.bulkops.domain.dto.QueryRequest;
import org.folio.bulkops.domain.entity.BulkOperation;
import org.folio.bulkops.domain.entity.BulkOperationDataProcessing;
import org.folio.bulkops.domain.entity.BulkOperationExecution;
import org.folio.bulkops.exception.BadRequestException;
import org.folio.bulkops.exception.BulkOperationException;
import org.folio.bulkops.exception.ConverterException;
import org.folio.bulkops.exception.IllegalOperationStateException;
import org.folio.bulkops.exception.NotFoundException;
import org.folio.bulkops.exception.OptimisticLockingException;
import org.folio.bulkops.exception.ServerErrorException;
import org.folio.bulkops.processor.DataProcessorFactory;
import org.folio.bulkops.processor.UpdatedEntityHolder;
import org.folio.bulkops.repository.BulkOperationDataProcessingRepository;
import org.folio.bulkops.repository.BulkOperationExecutionRepository;
import org.folio.bulkops.repository.BulkOperationRepository;
import org.folio.bulkops.util.Utils;
import org.folio.querytool.domain.dto.SubmitQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
@RequiredArgsConstructor
public class BulkOperationService {
  public static final String FILE_UPLOADING_FAILED_REASON = "File uploading failed, reason: %s";
  public static final String STEP_S_IS_NOT_APPLICABLE_FOR_BULK_OPERATION_STATUS = "Step %s is not applicable for bulk operation status %s";
  public static final String ERROR_STARTING_BULK_OPERATION = "Error starting Bulk Operation: ";
  @Value("${application.file-uploading.max-retry-count}")
  private int maxRetryCount;

  private final BulkOperationRepository bulkOperationRepository;
  private final DataExportSpringClient dataExportSpringClient;
  private final BulkEditClient bulkEditClient;
  private final RuleService ruleService;
  private final BulkOperationDataProcessingRepository dataProcessingRepository;
  private final BulkOperationExecutionRepository executionRepository;
  private final RemoteFileSystemClient remoteFileSystemClient;
  private final ObjectMapper objectMapper;
  private final DataProcessorFactory dataProcessorFactory;
  private final ErrorService errorService;
  private final LogFilesService logFilesService;
  private final RecordUpdateService recordUpdateService;
  private final EntityTypeService entityTypeService;
  private final QueryService queryService;

  private static final int OPERATION_UPDATING_STEP = 100;
  private static final String PREVIEW_JSON_PATH_TEMPLATE = "%s/json/%s-Updates-Preview-%s.json";
  private static final String PREVIEW_CSV_PATH_TEMPLATE = "%s/%s-Updates-Preview-%s.csv";
  private static final String CHANGED_JSON_PATH_TEMPLATE = "%s/json/%s-Changed-Records-%s.json";
  private static final String CHANGED_CSV_PATH_TEMPLATE = "%s/%s-Changed-Records-%s.csv";

  private final ExecutorService executor = Executors.newCachedThreadPool();

  public BulkOperation uploadCsvFile(EntityType entityType, IdentifierType identifierType, boolean manual, UUID operationId, UUID xOkapiUserId, MultipartFile multipartFile) {

    String errorMessage = null;
    BulkOperation operation;

    if (manual) {
      if (operationId == null) {
        throw new NotFoundException("File uploading failed, reason: query parameter operationId is required for csv approach");
      } else {

        operation = bulkOperationRepository.findById(operationId)
          .orElseThrow(() -> new NotFoundException("Bulk operation was not found by id=" + operationId));

        try {
          var linkToThePreviewFile = remoteFileSystemClient.put(multipartFile.getInputStream(), String.format(PREVIEW_CSV_PATH_TEMPLATE, operation.getId(), LocalDate.now(), FilenameUtils.getBaseName(operation.getLinkToTriggeringCsvFile())));
          operation.setLinkToModifiedRecordsCsvFile(linkToThePreviewFile);

          var numOfLines = remoteFileSystemClient.getNumOfLines(linkToThePreviewFile) - 1;
          if (operation.getTotalNumOfRecords() == 0) {
            operation.setTotalNumOfRecords(numOfLines);
          }
          operation.setProcessedNumOfRecords(numOfLines);
          operation.setMatchedNumOfRecords(numOfLines);

        } catch (Exception e) {
          log.error(ERROR_STARTING_BULK_OPERATION + e.getCause());
          errorMessage = format(FILE_UPLOADING_FAILED_REASON, e.getMessage());
        }
      }
      operation.setApproach(MANUAL);
    } else {
      operation = bulkOperationRepository.save(BulkOperation.builder()
        .id(UUID.randomUUID())
        .entityType(entityType)
        .identifierType(identifierType)
        .status(NEW)
        .startTime(LocalDateTime.now())
        .build());
      try {
        var linkToTriggeringFile = remoteFileSystemClient.put(multipartFile.getInputStream(), operation.getId() + "/" + multipartFile.getOriginalFilename());
        operation.setLinkToTriggeringCsvFile(linkToTriggeringFile);
      } catch (Exception e) {
        log.error(ERROR_STARTING_BULK_OPERATION + e);
        errorMessage = format(FILE_UPLOADING_FAILED_REASON, e.getMessage());
      }
    }

    if (nonNull(errorMessage)) {
      log.error(errorMessage);
      operation.setStatus(FAILED);
      operation.setErrorMessage(errorMessage);
      operation.setEndTime(LocalDateTime.now());
    }

    operation.setUserId(xOkapiUserId);

    return bulkOperationRepository.save(operation);
  }

  public BulkOperation triggerByQuery(UUID userId, QueryRequest queryRequest) {
    var submitQuery = new SubmitQuery()
      .fqlQuery(queryRequest.getFqlQuery())
      .entityTypeId(queryRequest.getEntityTypeId());
    var queryId = queryService.executeQuery(submitQuery);
    var entityType = entityTypeService.getEntityTypeById(submitQuery.getEntityTypeId());
    return bulkOperationRepository.save(BulkOperation.builder()
        .id(UUID.randomUUID())
        .entityType(entityType)
        .approach(QUERY)
        .identifierType(IdentifierType.ID)
        .status(EXECUTING_QUERY)
        .startTime(LocalDateTime.now())
        .userId(userId)
        .fqlQuery(submitQuery.getFqlQuery())
        .fqlQueryId(queryId)
        .userFriendlyQuery(queryRequest.getUserFriendlyQuery())
      .build());
  }

  public void confirm(BulkOperation operation)  {

    operation.setProcessedNumOfRecords(0);
    var operationId = operation.getId();

    var clazz = resolveEntityClass(operation.getEntityType());
    var ruleCollection = ruleService.getRules(operationId);

    var dataProcessing = dataProcessingRepository.save(BulkOperationDataProcessing.builder()
      .bulkOperationId(operation.getId())
      .status(StatusType.ACTIVE)
      .startTime(LocalDateTime.now())
      .totalNumOfRecords(operation.getTotalNumOfRecords())
      .processedNumOfRecords(0)
      .build());

    var triggeringFileName = FilenameUtils.getBaseName(operation.getLinkToTriggeringCsvFile());
    var modifiedJsonFileName = String.format(PREVIEW_JSON_PATH_TEMPLATE, operationId, LocalDate.now(), triggeringFileName);
    var modifiedPreviewCsvFileName = String.format(PREVIEW_CSV_PATH_TEMPLATE, operationId, LocalDate.now(), triggeringFileName);

    try (var readerForMatchedJsonFile = remoteFileSystemClient.get(operation.getLinkToMatchedRecordsJsonFile());
         var writerForModifiedPreviewCsvFile = remoteFileSystemClient.writer(modifiedPreviewCsvFileName);
         var writerForModifiedJsonFile = remoteFileSystemClient.writer(modifiedJsonFileName)) {

      var csvWriter = new BulkOperationsEntityCsvWriter(writerForModifiedPreviewCsvFile, clazz);

      var iterator = objectMapper.readValues(new JsonFactory().createParser(readerForMatchedJsonFile), clazz);

      var processedNumOfRecords = 0;

      if (iterator.hasNext()) {
        operation.setLinkToModifiedRecordsCsvFile(modifiedPreviewCsvFileName);
      }

      while (iterator.hasNext()) {
        var original = iterator.next();
        var modified = processUpdate(original, operation, ruleCollection, clazz);

        if (Objects.nonNull(modified)) {
          // Prepare CSV for download and preview
          writeToCsv(operation, csvWriter, modified.getPreview());
          var modifiedRecord = objectMapper.writeValueAsString(modified.getUpdated()) + LF;
          writerForModifiedJsonFile.write(modifiedRecord);
        }

        processedNumOfRecords++;

        dataProcessing = dataProcessing
          .withStatus(iterator.hasNext() ? StatusType.ACTIVE : StatusType.COMPLETED)
          .withEndTime(iterator.hasNext() ? null : LocalDateTime.now());

        if (processedNumOfRecords - dataProcessing.getProcessedNumOfRecords() > OPERATION_UPDATING_STEP) {
          dataProcessing.setProcessedNumOfRecords(processedNumOfRecords);
          dataProcessingRepository.save(dataProcessing);
        }
      }

      operation.setLinkToModifiedRecordsJsonFile(modifiedJsonFileName);

      dataProcessing.setProcessedNumOfRecords(processedNumOfRecords);
      dataProcessingRepository.save(dataProcessing);

      operation.setApproach(IN_APP);
      operation.setStatus(OperationStatusType.REVIEW_CHANGES);
      operation.setProcessedNumOfRecords(processedNumOfRecords);
      bulkOperationRepository.findById(operation.getId()).ifPresent(op -> operation.setCommittedNumOfErrors(op.getCommittedNumOfErrors()));
    } catch (Exception e) {
      log.error(e);
      dataProcessingRepository.save(dataProcessing
        .withStatus(StatusType.FAILED)
        .withEndTime(LocalDateTime.now()));
      operation.setStatus(OperationStatusType.FAILED);
      operation.setEndTime(LocalDateTime.now());
      operation.setErrorMessage("Confirm changes operation failed, reason: " + e.getMessage());
    } finally {
      bulkOperationRepository.save(operation);
    }
  }

  public void writeToCsv(BulkOperation operation, BulkOperationsEntityCsvWriter csvWriter, BulkOperationsEntity bean) throws CsvRequiredFieldEmptyException, CsvDataTypeMismatchException {
    try {
      csvWriter.write(bean);
    } catch (ConverterException e) {
      if (APPLY_CHANGES.equals(operation.getStatus())) {
        log.error("Record {}, field: {}, converter exception: {}", bean.getIdentifier(operation.getIdentifierType()), e.getField().getName(), e.getMessage());
      } else {
        errorService.saveError(operation.getId(), bean.getIdentifier(operation.getIdentifierType()), format(FIELD_ERROR_MESSAGE_PATTERN, e.getField().getName(), e.getMessage()));
      }
      writeToCsv(operation, csvWriter, bean);
    }
  }

  private UpdatedEntityHolder<? extends BulkOperationsEntity> processUpdate(BulkOperationsEntity original, BulkOperation operation, BulkOperationRuleCollection rules, Class<? extends BulkOperationsEntity> entityClass) {
    var processor = dataProcessorFactory.getProcessorFromFactory(entityClass);
    UpdatedEntityHolder<BulkOperationsEntity> modified = null;

    try {
      modified = processor.process(original.getIdentifier(operation.getIdentifierType()), original, rules);
    } catch (Exception e) {
      log.error("Failed to modify entity, reason:" + e.getMessage());
    }

    return modified;
  }

  public void commit(BulkOperation operation) {

    var operationId = operation.getId();
    operation.setCommittedNumOfRecords(0);
    operation.setStatus(OperationStatusType.APPLY_CHANGES);
    operation.setTotalNumOfRecords(operation.getMatchedNumOfRecords());

    operation = bulkOperationRepository.save(operation);

    if (StringUtils.isNotEmpty(operation.getLinkToModifiedRecordsJsonFile())) {
      var entityClass = resolveEntityClass(operation.getEntityType());

      var execution = executionRepository.save(BulkOperationExecution.builder()
        .bulkOperationId(operationId)
        .startTime(LocalDateTime.now())
        .processedRecords(0)
        .status(StatusType.ACTIVE)
        .build());

      var triggeringFileName = FilenameUtils.getBaseName(operation.getLinkToTriggeringCsvFile());
      var resultJsonFileName = String.format(CHANGED_JSON_PATH_TEMPLATE, operation.getId(), LocalDate.now(), triggeringFileName);
      var resultCsvFileName = String.format(CHANGED_CSV_PATH_TEMPLATE, operation.getId(), LocalDate.now(), triggeringFileName);

      try (var originalFileReader = new InputStreamReader(remoteFileSystemClient.get(operation.getLinkToMatchedRecordsJsonFile()));
           var modifiedFileReader = new InputStreamReader(remoteFileSystemClient.get(operation.getLinkToModifiedRecordsJsonFile()));
           var writerForResultCsvFile = remoteFileSystemClient.writer(resultCsvFileName);
           var writerForResultJsonFile = remoteFileSystemClient.writer(resultJsonFileName)) {

        var originalFileParser = new JsonFactory().createParser(originalFileReader);
        var originalFileIterator = objectMapper.readValues(originalFileParser, entityClass);

        var modifiedFileParser = new JsonFactory().createParser(modifiedFileReader);
        var modifiedFileIterator = objectMapper.readValues(modifiedFileParser, entityClass);

        var csvWriter = new BulkOperationsEntityCsvWriter(writerForResultCsvFile, entityClass);

        int processedNumOfRecords = 0;

        while (hasNextRecord(originalFileIterator, modifiedFileIterator)) {
          var original = originalFileIterator.next();
          var modified = modifiedFileIterator.next();

          processedNumOfRecords++;

          try {
            var result = recordUpdateService.updateEntity(original, modified, operation);
            if (result != original) {
              var hasNextRecord = hasNextRecord(originalFileIterator, modifiedFileIterator);
              writerForResultJsonFile.write(objectMapper.writeValueAsString(result) + (hasNextRecord ? LF : EMPTY));
              writeToCsv(operation, csvWriter, result);
            }
          } catch (OptimisticLockingException e) {
            errorService.saveError(operationId, original.getIdentifier(operation.getIdentifierType()), e.getCsvErrorMessage(), e.getUiErrorMessage(), e.getLinkToFailedEntity());
          } catch (Exception e) {
            errorService.saveError(operationId, original.getIdentifier(operation.getIdentifierType()), e.getMessage());
          }
          execution = execution
            .withStatus(originalFileIterator.hasNext() ? StatusType.ACTIVE : StatusType.COMPLETED)
            .withEndTime(originalFileIterator.hasNext() ? null : LocalDateTime.now());
          if (processedNumOfRecords - execution.getProcessedRecords() > OPERATION_UPDATING_STEP) {
            execution.setProcessedRecords(processedNumOfRecords);
            executionRepository.save(execution);
          }
        }

        execution.setProcessedRecords(processedNumOfRecords);
        operation.setProcessedNumOfRecords(operation.getCommittedNumOfRecords());
        operation.setEndTime(LocalDateTime.now());
        if (operation.getCommittedNumOfRecords() > 0) {
          operation.setLinkToCommittedRecordsCsvFile(resultCsvFileName);
          operation.setLinkToCommittedRecordsJsonFile(resultJsonFileName);
        }
      } catch (Exception e) {
        execution = execution
          .withStatus(StatusType.FAILED)
          .withEndTime(LocalDateTime.now());
        operation.setStatus(OperationStatusType.FAILED);
        operation.setEndTime(LocalDateTime.now());
        operation.setErrorMessage(e.getMessage());
      }
      executionRepository.save(execution);
    }

    var linkToCommittingErrorsFile = errorService.uploadErrorsToStorage(operationId);
    operation.setLinkToCommittedRecordsErrorsCsvFile(linkToCommittingErrorsFile);

    if (!FAILED.equals(operation.getStatus())) {
      operation.setStatus(isEmpty(linkToCommittingErrorsFile) ? COMPLETED : COMPLETED_WITH_ERRORS);
    }
    var operationOpt = bulkOperationRepository.findById(operation.getId());
    if (operationOpt.isPresent()) {
      operation.setCommittedNumOfErrors(operationOpt.get().getCommittedNumOfErrors());
    }
    bulkOperationRepository.save(operation);
  }

  public BulkOperation startBulkOperation(UUID bulkOperationId, UUID xOkapiUserId, BulkOperationStart bulkOperationStart) {
    var step = bulkOperationStart.getStep();
    var approach = bulkOperationStart.getApproach();
    BulkOperation operation = bulkOperationRepository.findById(bulkOperationId)
        .orElseThrow(() -> new NotFoundException("Bulk operation was not found bu id=" + bulkOperationId));
    operation.setUserId(xOkapiUserId);

    String errorMessage = null;
    if (UPLOAD == step) {
      errorMessage = executeDataExportJob(step, approach, operation, errorMessage);

      if (nonNull(errorMessage)) {
        log.error(errorMessage);
        operation.setStatus(FAILED);
        operation.setErrorMessage(errorMessage);
        operation.setEndTime(LocalDateTime.now());
      }
      bulkOperationRepository.save(operation);
      return operation;
    } else if (BulkOperationStep.EDIT == step) {
      errorService.deleteErrorsByBulkOperationId(bulkOperationId);
      operation.setCommittedNumOfErrors(0);
      if (DATA_MODIFICATION.equals(operation.getStatus()) || REVIEW_CHANGES.equals(operation.getStatus())) {
        if (MANUAL == approach) {
          executor.execute(getRunnableWithCurrentFolioContext(() -> apply(operation)));
        } else {
          logFilesService.removeModifiedFiles(operation);
          executor.execute(getRunnableWithCurrentFolioContext(() -> confirm(operation)));
        }
        return operation;
      } else {
        throw new BadRequestException(format(STEP_S_IS_NOT_APPLICABLE_FOR_BULK_OPERATION_STATUS, step, operation.getStatus()));
      }
    } else if (BulkOperationStep.COMMIT == step) {
      if (REVIEW_CHANGES.equals(operation.getStatus())) {
        executor.execute(getRunnableWithCurrentFolioContext(() -> commit(operation)));
        return operation;
      } else {
        throw new BadRequestException(format(STEP_S_IS_NOT_APPLICABLE_FOR_BULK_OPERATION_STATUS, step, operation.getStatus()));
      }
    } else {
      throw new IllegalOperationStateException("Bulk operation cannot be started, reason: invalid state: " + operation.getStatus());
    }
  }

  private String executeDataExportJob(BulkOperationStep step, ApproachType approach, BulkOperation operation, String errorMessage) {
    try {
      if (Set.of(NEW, SAVED_IDENTIFIERS).contains(operation.getStatus())) {
        if (MANUAL != approach) {
          var job = dataExportSpringClient.upsertJob(Job.builder()
            .type(ExportType.BULK_EDIT_IDENTIFIERS)
            .entityType(operation.getEntityType())
            .exportTypeSpecificParameters(new ExportTypeSpecificParameters())
            .identifierType(operation.getIdentifierType()).build());
          operation.setDataExportJobId(job.getId());
          bulkOperationRepository.save(operation);

          if (JobStatus.SCHEDULED.equals(job.getStatus())) {
            uploadCsvFile(job.getId(), new FolioMultiPartFile(FilenameUtils.getName(operation.getLinkToTriggeringCsvFile()), "application/json", remoteFileSystemClient.get(operation.getLinkToTriggeringCsvFile())));
            job = dataExportSpringClient.getJob(job.getId());

            if (JobStatus.FAILED.equals(job.getStatus())) {
              errorMessage = "Data export job failed";
            } else {
              operation.setStatus(RETRIEVING_RECORDS);
            }
          } else {
            errorMessage = format("File uploading failed - invalid job status: %s (expected: SCHEDULED)", job.getStatus().getValue());
          }
        }
      } else {
        throw new BadRequestException(format(STEP_S_IS_NOT_APPLICABLE_FOR_BULK_OPERATION_STATUS, step, operation.getStatus()));
      }
    } catch (Exception e) {
      log.error(ERROR_STARTING_BULK_OPERATION + e.getCause());
      errorMessage = format(FILE_UPLOADING_FAILED_REASON, e.getMessage());
    }
    return errorMessage;
  }

  public void apply(BulkOperation operation) {
    operation.setProcessedNumOfRecords(0);
    var bulkOperationId = operation.getId();
    var linkToModifiedRecordsCsvFile = operation.getLinkToModifiedRecordsCsvFile();
    var linkToModifiedRecordsJsonFile = String.format(PREVIEW_JSON_PATH_TEMPLATE, bulkOperationId, LocalDate.now(), FilenameUtils.getBaseName(operation.getLinkToTriggeringCsvFile()));
    try (Reader readerForModifiedCsvFile = new InputStreamReader(remoteFileSystemClient.get(linkToModifiedRecordsCsvFile));
         Writer writerForModifiedJsonFile = remoteFileSystemClient.writer(linkToModifiedRecordsJsonFile)) {

      var clazz = resolveEntityClass(operation.getEntityType());

      CsvToBean<BulkOperationsEntity> csvToBean = new CsvToBeanBuilder<BulkOperationsEntity>(readerForModifiedCsvFile)
        .withType(clazz)
        .withSkipLines(1)
        .withThrowExceptions(false)
        .build();

      var modifiedCsvFileIterator = csvToBean.iterator();

      var processedNumOfRecords = 0;

      while (modifiedCsvFileIterator.hasNext()) {
        var modifiedEntity = modifiedCsvFileIterator.next();
        var modifiedEntityString = objectMapper.writeValueAsString(modifiedEntity) + (modifiedCsvFileIterator.hasNext() ? LF : EMPTY);

        writerForModifiedJsonFile.write(modifiedEntityString);
        processedNumOfRecords++;
        if (processedNumOfRecords - operation.getProcessedNumOfRecords() > OPERATION_UPDATING_STEP) {
          operation.setProcessedNumOfRecords(processedNumOfRecords);
          bulkOperationRepository.save(operation);
        }
      }
      csvToBean.getCapturedExceptions().forEach(e -> errorService.saveError(operation.getId(), Utils.getIdentifierForManualApproach(e.getLine(), operation.getIdentifierType()), e.getMessage()));
      csvToBean.getCapturedExceptions().clear();
      operation.setProcessedNumOfRecords(processedNumOfRecords);
      operation.setStatus(REVIEW_CHANGES);
      operation.setLinkToModifiedRecordsJsonFile(linkToModifiedRecordsJsonFile);
      bulkOperationRepository.findById(operation.getId()).ifPresent(op -> operation.setCommittedNumOfErrors(op.getCommittedNumOfErrors()));
    } catch (Exception e) {
      operation.setErrorMessage("Error applying changes: " + e.getCause());

      throw new ServerErrorException(e.getMessage());
    } finally {
      bulkOperationRepository.save(operation);
    }
  }

  private String uploadCsvFile(UUID dataExportJobId, MultipartFile file) throws BulkOperationException {
    var retryCount = 0;
    while (true) {
      try {
        return bulkEditClient.uploadFile(dataExportJobId, file);
      } catch (NotFoundException e) {
        if (++retryCount == maxRetryCount) {
          throw new BulkOperationException("Failed to upload file with identifiers: data export job was not found");
        }
      }
    }
  }

  public void clearOperationProcessing(BulkOperation operation) {
    var processing = dataProcessingRepository.findById(operation.getId());

    if (processing.isPresent()) {
      dataProcessingRepository.deleteById(processing.get().getBulkOperationId());

      operation.setStatus(DATA_MODIFICATION);
      bulkOperationRepository.save(operation);
    }
  }

  public BulkOperation getOperationById(UUID bulkOperationId) {
    var operation = getBulkOperationOrThrow(bulkOperationId);
    return switch (operation.getStatus()) {
      case EXECUTING_QUERY -> queryService.checkQueryExecutionStatus(operation);
      case SAVED_IDENTIFIERS -> startBulkOperation(operation.getId(), operation.getUserId(), new BulkOperationStart()
        .step(UPLOAD)
        .approach(IN_APP)
        .entityType(operation.getEntityType())
        .entityCustomIdentifierType(IdentifierType.ID));
      case DATA_MODIFICATION -> {
        var processing = dataProcessingRepository.findById(bulkOperationId);
        if (processing.isPresent() && StatusType.ACTIVE.equals(processing.get().getStatus())) {
          operation.setProcessedNumOfRecords(processing.get().getProcessedNumOfRecords());
        }
        yield operation;
      }
      case APPLY_CHANGES -> {
        var execution = executionRepository.findByBulkOperationId(bulkOperationId);
        if (execution.isPresent() && StatusType.ACTIVE.equals(execution.get().getStatus())) {
          operation.setProcessedNumOfRecords(execution.get().getProcessedRecords());
        }
        yield operation;
      }
      default -> operation;
    };
  }

  public BulkOperation getBulkOperationOrThrow(UUID operationId) {
    return bulkOperationRepository.findById(operationId)
      .orElseThrow(() -> new NotFoundException("BulkOperation was not found by id=" + operationId));
  }

  private boolean hasNextRecord(MappingIterator<? extends BulkOperationsEntity> originalFileIterator, MappingIterator<? extends BulkOperationsEntity> modifiedFileIterator) {
    return originalFileIterator.hasNext() && modifiedFileIterator.hasNext();
  }

  public void cancelOperationById(UUID operationId) {
    var operation = getBulkOperationOrThrow(operationId);
    if (Set.of(NEW, RETRIEVING_RECORDS, SAVING_RECORDS_LOCALLY).contains(operation.getStatus())) {
      logFilesService.removeTriggeringAndMatchedRecordsFiles(operation);
    } else if (Set.of(DATA_MODIFICATION, REVIEW_CHANGES).contains(operation.getStatus()) && MANUAL.equals(operation.getApproach())) {
      logFilesService.removeModifiedFiles(operation);
    } else {
      throw new IllegalOperationStateException(String.format("Operation with status %s cannot be cancelled", operation.getStatus()));
    }
    bulkOperationRepository.save(operation);
  }
}
