package io.mosip.registration.service.packet.impl;

import static io.mosip.kernel.core.util.JsonUtils.javaObjectToJsonString;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.support.RetryTemplateBuilder;
import org.springframework.stereotype.Service;

import com.google.common.annotations.VisibleForTesting;

import io.mosip.commons.packet.spi.IPacketCryptoService;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.FileUtils;
import io.mosip.kernel.core.util.HMACUtils2;
import io.mosip.kernel.core.util.JsonUtils;
import io.mosip.kernel.core.util.exception.JsonMappingException;
import io.mosip.kernel.core.util.exception.JsonParseException;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationClientStatusCode;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.dao.RegistrationDAO;
import io.mosip.registration.dto.ErrorResponseDTO;
import io.mosip.registration.dto.PacketStatusDTO;
import io.mosip.registration.dto.RegistrationDataDto;
import io.mosip.registration.dto.RegistrationPacketSyncDTO;
import io.mosip.registration.dto.ResponseDTO;
import io.mosip.registration.dto.SuccessResponseDTO;
import io.mosip.registration.dto.SyncRegistrationDTO;
import io.mosip.registration.entity.Registration;
import io.mosip.registration.exception.ConnectionException;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.service.BaseService;
import io.mosip.registration.service.sync.PacketSynchService;
import lombok.NonNull;
import lombok.SneakyThrows;

/**
 * This class invokes the external MOSIP service 'Packet Sync' to sync the
 * packet ids, which are ready for upload to the server from client. The packet
 * upload can't be done, without synching the packet ids to the server. While
 * sending this request, the data would be encrypted using MOSIP public key and
 * same can be decrypted at Server end using the respective private key.
 *
 * @author saravanakumar gnanaguru
 *
 */
@Service
public class PacketSynchServiceImpl extends BaseService implements PacketSynchService {

	private static final Logger LOGGER = AppConfig.getLogger(PacketSynchServiceImpl.class);

	@Autowired
	private RegistrationDAO syncRegistrationDAO;

	@Autowired
	protected AuditManagerService auditFactory;

	@Autowired
	@Qualifier("OfflinePacketCryptoServiceImpl")
	private IPacketCryptoService offlinePacketCryptoServiceImpl;

	@Autowired
	private RegistrationDAO registrationDAO;

	@Value("${mosip.registration.rid_sync_batch_size:10}")
	private int batchCount;

	private RetryTemplate retryTemplate;

	@PostConstruct
	public void init() {
		FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
		backOffPolicy.setBackOffPeriod((Long) ApplicationContext.map().getOrDefault("mosip.registration.retry.delay.packet.sync", 1000l));

		SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
		retryPolicy.setMaxAttempts((Integer) ApplicationContext.map().getOrDefault("mosip.registration.retry.maxattempts.packet.sync", 2));

		retryTemplate = new RetryTemplateBuilder()
				.retryOn(ConnectionException.class)
				.customPolicy(retryPolicy)
				.customBackoff(backOffPolicy)
				.build();
	}


	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * io.mosip.registration.service.sync.PacketSynchService#fetchPacketsToBeSynched
	 * ()
	 */
	@Override
	public List<PacketStatusDTO> fetchPacketsToBeSynched() {
		LOGGER.info("Fetch the packets that needs to be synced to the server");
		List<PacketStatusDTO> idsToBeSynched = new ArrayList<>();
		List<Registration> packetsToBeSynched = syncRegistrationDAO.fetchPacketsToUpload(
				RegistrationConstants.PACKET_STATUS_UPLOAD, RegistrationConstants.SERVER_STATUS_RESEND);
		packetsToBeSynched.forEach(reg -> {
			if (reg.getServerStatusCode() == null
					|| (reg.getClientStatusTimestamp() != null && reg.getServerStatusTimestamp() != null
					&& !(RegistrationConstants.SERVER_STATUS_RESEND.equalsIgnoreCase(reg.getServerStatusCode())
					&& reg.getClientStatusTimestamp().after(reg.getServerStatusTimestamp())))) {
				idsToBeSynched.add(preparePacketStatusDto(reg));
			}
		});
		return idsToBeSynched;
	}


	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * io.mosip.registration.service.sync.PacketSynchService#fetchSynchedPacket(java
	 * .lang.String)
	 */
	@Override
	public Boolean fetchSynchedPacket(@NonNull String rId) {
		Registration reg = syncRegistrationDAO
				.getRegistrationById(RegistrationClientStatusCode.META_INFO_SYN_SERVER.getCode(), rId);
		return reg != null && !reg.getId().isEmpty();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * io.mosip.registration.service.sync.PacketSynchService#syncPacket(java
	 * .lang.String)
	 */
	@Override
	public ResponseDTO syncPacket(String triggerPoint) {
		LOGGER.info("Syncing specific number of packets to the server with count {}", batchCount);
		ResponseDTO responseDTO = new ResponseDTO();
		try {

			syncRIDToServerWithRetryWrapper(triggerPoint, null);
			setSuccessResponse(responseDTO, RegistrationConstants.SUCCESS, null);

		} catch (ConnectionException | RegBaseCheckedException | JsonProcessingException exception) {
			LOGGER.error("Exception in RID sync", exception);
			setErrorResponse(responseDTO, exception.getMessage(), null);
		}
		setErrorResponse(responseDTO, RegistrationConstants.ERROR, null);
		return responseDTO;
	}


	@Override
	public ResponseDTO syncPacket(@NonNull String triggerPoint, @NonNull List<String> rids) {
		LOGGER.info("Syncing specific rids to the server with count {}", rids.size());
		ResponseDTO responseDTO = new ResponseDTO();
		try {

			syncRIDToServerWithRetryWrapper(triggerPoint, rids);
			setSuccessResponse(responseDTO, RegistrationConstants.SUCCESS, null);

		} catch (ConnectionException | RegBaseCheckedException | JsonProcessingException exception) {
			LOGGER.error("Exception in RID sync", exception);
			setErrorResponse(responseDTO, exception.getMessage(), null);
		}
		return responseDTO;
	}

	@Override
	public ResponseDTO syncAllPackets(String triggerPoint) {
		return syncPacket(triggerPoint);
	}

	private void syncRIDToServerWithRetryWrapper(String triggerPoint, List<String> RIDs)
			throws RegBaseCheckedException, JsonProcessingException, ConnectionException {
		RetryCallback<Boolean, ConnectionException> retryCallback = new RetryCallback<Boolean, ConnectionException>() {
			@SneakyThrows
			@Override
			public Boolean doWithRetry(RetryContext retryContext) throws ConnectionException {
				LOGGER.info("Currently in Retry wrapper. Current counter : {}", retryContext.getRetryCount());
				syncRIDToServer(triggerPoint, RIDs);
				return true;
			}
		};
		retryTemplate.execute(retryCallback);
	}

	@VisibleForTesting
	private synchronized void syncRIDToServer(String triggerPoint, List<String> RIDs)
			throws RegBaseCheckedException, JsonProcessingException, ConnectionException {
		//Precondition check, proceed only if met, otherwise throws exception
		proceedWithPacketSync();

		List<Registration> registrations = (RIDs != null) ? registrationDAO.get(RIDs) :
				registrationDAO.getPacketsToBeSynched(RegistrationConstants.PACKET_STATUS, batchCount);

		List<SyncRegistrationDTO> syncDtoList = getPacketSyncDtoList(registrations);
		if(syncDtoList == null || syncDtoList.isEmpty())
			return;

		RegistrationPacketSyncDTO registrationPacketSyncDTO = new RegistrationPacketSyncDTO();
		registrationPacketSyncDTO
				.setRequesttime(DateUtils.formatToISOString(DateUtils.getUTCCurrentDateTime()));
		registrationPacketSyncDTO.setSyncRegistrationDTOs(syncDtoList);
		registrationPacketSyncDTO.setId(RegistrationConstants.PACKET_SYNC_STATUS_ID);
		registrationPacketSyncDTO.setVersion(RegistrationConstants.PACKET_SYNC_VERSION);
		String regId = registrationPacketSyncDTO.getSyncRegistrationDTOs().get(0).getRegistrationId();
		ResponseDTO response = syncPacketsToServer(CryptoUtil.encodeBase64(offlinePacketCryptoServiceImpl
				.encrypt(regId, javaObjectToJsonString(registrationPacketSyncDTO).getBytes())), triggerPoint);

		if (response != null && response.getSuccessResponseDTO() != null) {
			for (SyncRegistrationDTO dto : syncDtoList) {
				String status = (String) response.getSuccessResponseDTO().getOtherAttributes()
						.get(dto.getRegistrationId());

				if (status != null && status.equalsIgnoreCase(RegistrationConstants.SUCCESS)) {
					PacketStatusDTO packetStatusDTO = new PacketStatusDTO();
					packetStatusDTO.setFileName(dto.getRegistrationId());
					packetStatusDTO.setPacketClientStatus(RegistrationClientStatusCode.META_INFO_SYN_SERVER.getCode());
					// TODO - check on re-register status logic
					syncRegistrationDAO.updatePacketSyncStatus(packetStatusDTO);
				}
			}
		}
		LOGGER.debug("Sync the packets to the server ending");
	}


	private List<SyncRegistrationDTO> getPacketSyncDtoList(@NonNull List<Registration> registrations) {
		List<SyncRegistrationDTO> syncDtoList = new ArrayList<>();
		for(Registration registration : registrations) {
			if(registration.getClientStatusCode().equals(RegistrationConstants.SYNCED_STATUS))
				continue;

			SyncRegistrationDTO syncDto = new SyncRegistrationDTO();
			syncDto.setRegistrationId(registration.getId());
			syncDto.setRegistrationType(registration.getStatusCode().toUpperCase());

			try {
				if (registration.getAdditionalInfo() != null) {
					String additionalInfo = new String(registration.getAdditionalInfo());
					RegistrationDataDto registrationDataDto = (RegistrationDataDto) JsonUtils
							.jsonStringToJavaObject(RegistrationDataDto.class, additionalInfo);
					syncDto.setName(registrationDataDto.getName());
					syncDto.setPhone(registrationDataDto.getPhone());
					syncDto.setEmail(registrationDataDto.getEmail());
					syncDto.setLangCode(registrationDataDto.getLangCode() != null ?
							registrationDataDto.getLangCode().split(",")[0] :
							ApplicationContext.applicationLanguage());
				}
			} catch (JsonParseException | JsonMappingException | io.mosip.kernel.core.exception.IOException exception) {
				LOGGER.error(exception.getMessage(), exception);
			}

			try (FileInputStream fis = new FileInputStream(FileUtils.getFile(registration.getAckFilename().replace(
					RegistrationConstants.ACKNOWLEDGEMENT_FILE_EXTENSION, RegistrationConstants.ZIP_FILE_EXTENSION)))) {
				byte[] byteArray = new byte[(int) fis.available()];
				fis.read(byteArray);
				syncDto.setPacketHashValue(HMACUtils2.digestAsPlainText(byteArray));
				syncDto.setPacketSize(BigInteger.valueOf(byteArray.length));
			} catch (IOException | NoSuchAlgorithmException ioException) {
				LOGGER.error(ioException.getMessage(), ioException);
			}

			if (RegistrationClientStatusCode.RE_REGISTER.getCode()
					.equalsIgnoreCase(registration.getClientStatusCode())) {
				syncDto.setSupervisorStatus(RegistrationConstants.CLIENT_STATUS_APPROVED);
			} else {
				syncDto.setSupervisorStatus(registration.getClientStatusCode());
			}
			syncDto.setSupervisorComment(registration.getClientStatusComments());
			syncDtoList.add(syncDto);
		}
		return syncDtoList;
	}

	/**
	 * This method makes the actual service call to push the packet sync related
	 * data to server. It makes only external service call and doesn't have any db
	 * call.
	 *
	 * @param encodedString
	 *            the sync dto list
	 * @param triggerPoint
	 *            the trigger point
	 * @return the response DTO
	 * @throws RegBaseCheckedException
	 *             the reg base checked exception
	 * @throws ConnectionException
	 *             the ConnectionException
	 */
	@VisibleForTesting
	private ResponseDTO syncPacketsToServer(@NonNull String encodedString, @NonNull String triggerPoint)
			throws RegBaseCheckedException, ConnectionException {
		LOGGER.info("Sync the packets to the server");
		ResponseDTO responseDTO = new ResponseDTO();

		try {
			LinkedHashMap<String, Object> response = (LinkedHashMap<String, Object>) serviceDelegateUtil
					.post(RegistrationConstants.PACKET_SYNC, javaObjectToJsonString(encodedString), triggerPoint);
			if (response.get("response") != null) {
				SuccessResponseDTO successResponseDTO = new SuccessResponseDTO();
				Map<String, Object> statusMap = new WeakHashMap<>();
				for (LinkedHashMap<String, Object> responseMap : (List<LinkedHashMap<String, Object>>) response
						.get("response")) {
					statusMap.put((String) responseMap.get("registrationId"), responseMap.get("status"));
				}
				successResponseDTO.setOtherAttributes(statusMap);
				responseDTO.setSuccessResponseDTO(successResponseDTO);
			} else if (response.get("errors") != null) {
				List<ErrorResponseDTO> errorResponseDTOs = new ArrayList<>();
				ErrorResponseDTO errorResponseDTO = new ErrorResponseDTO();
				errorResponseDTO.setMessage(response.get("errors").toString());
				errorResponseDTOs.add(errorResponseDTO);
				responseDTO.setErrorResponseDTOs(errorResponseDTOs);
				LOGGER.error(response.get("errors").toString());
			}
		} catch (ConnectionException e) {
			throw e;
		} catch (JsonProcessingException | RuntimeException e) {
			LOGGER.error(e.getMessage(), e);
			throw new RegBaseCheckedException(RegistrationExceptionConstants.REG_PACKET_SYNC_EXCEPTION.getErrorCode(),
					RegistrationExceptionConstants.REG_PACKET_SYNC_EXCEPTION.getErrorMessage());
		}
		return responseDTO;
	}
}
