package com.nuoxin.virtual.rep.api.service;

import java.io.File;

import javax.annotation.Resource;
import javax.transaction.Transactional;

import com.nuoxin.virtual.rep.api.utils.SpeechRecognitionUtil;
import com.nuoxin.virtual.rep.api.utils.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.nuoxin.virtual.rep.api.common.constant.FileConstant;
import com.nuoxin.virtual.rep.api.common.util.StringUtils;
import com.nuoxin.virtual.rep.api.dao.DoctorCallInfoRepository;
import com.nuoxin.virtual.rep.api.entity.DoctorCallInfo;
import com.nuoxin.virtual.rep.api.entity.v2_5.VirtualDoctorCallInfoParams;
import com.nuoxin.virtual.rep.api.mybatis.DoctorMapper;
import com.nuoxin.virtual.rep.api.mybatis.VirtualDoctorCallInfoMapper;

@Transactional
@Service
public abstract class BaseCallBackImpl implements CallBackService {
	
	private static final Logger logger = LoggerFactory.getLogger(BaseCallBackImpl.class);

	@Value("${recording.file.path}")
	private String path;

	@Resource
	private OssService ossService;
	@Resource
	private FileService fileService;
	@Resource
	private VirtualDoctorCallInfoMapper callInfoMapper;
	@Resource
	private DoctorMapper doctorMapper;
	@Resource
	private DoctorCallInfoRepository callInfoDao;
	
	/**
	 * 父类通用回调处理
	 * @param result ConvertResult 对象
	 */
	protected void processCallBack(ConvertResult result) {
		String sinToken = result.getSinToken();
		String audioFileDownloadUrl = result.getMonitorFilenameUrl();
		String callOssUrl = this.processFile(audioFileDownloadUrl, sinToken);
		
		result.setMonitorFilenameUrl(callOssUrl);

		DoctorCallInfo info = this.getDoctorCallInfoBySinToken(sinToken);
		if (info == null) {
			logger.warn("无法获取 DoctorCallInfo 信息 sinToken:{}, 走插入表路线", sinToken);
			this.saveCallInfo(result);
		} else {
			logger.warn("可以获取 DoctorCallInfo 信息 sinToken:{}, 走修改表路线", sinToken);
			Long callId = info.getId();
			String statusName = info.getStatusName();
			if(this.flag(statusName)) {
				logger.warn("将 naxions私有 statueName:{} 回写至记录", statusName);
				result.setStatusName(statusName);
			}

			this.updateUrl(callOssUrl, result.getStatus(), result.getStatusName(), callId, result.getCallTime());
		}

		this.updateCallUrlText(sinToken, callOssUrl);
		logger.warn("回调执行成功! sinToken:{}, status:{}, statusName:{}, downloadUrl:{}", 
				sinToken, result.getStatus(), result.getStatusName(), callOssUrl);
	}

	/**
	 * 更新电话录音地址
	 * @param sinToken
	 * @param callOssUrl
	 */
	protected void updateCallUrlText(String sinToken, String callOssUrl) {
		if (StringUtil.isNotEmpty(sinToken) && StringUtil.isNotEmpty(callOssUrl)){
			try {
				String callText = SpeechRecognitionUtil.getSpeechRecognitionResult(callOssUrl);
				callInfoMapper.updateCallUrlText(sinToken, callText);
			}catch (Exception e){
				logger.error("BaseCallBackImpl updateCallUrlText(String sinToken, String callOssUrl) error !!! sinToken={}, callOssUrl={}", sinToken, callOssUrl, e);
			}

		}

	}

	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * 根据 sinToken 获取 DoctorCallInfo 信息
	 * @param sinToken 通讯唯一标识
	 * @return 成功返回 DoctorCallInfo,否则返回 null
	 */
	private DoctorCallInfo getDoctorCallInfoBySinToken(String sinToken) {
		return callInfoDao.findBySinToken(sinToken);
	}

	/**
	 * 文件处理(保存至本地及上传至阿里OSS)
	 * @param audioFileUrl 供应商提供的录音文件下载 URL
	 * @param sinToken 通讯唯一标识
	 * @return 返回 OSS URL
	 */
	protected String processFile(String audioFileUrl, String sinToken) {
		String fileName = sinToken.concat(FileConstant.MP3_SUFFIX);
		fileService.processLocalFile(audioFileUrl, fileName, path);
		
		String fullFileName = path.concat(fileName);
		String ossUrl = ossService.uploadFile(new File(fullFileName));
		
		// 这里走了补偿机制.即:当上传至阿里失败时写入回调时供应商传递过来的文件下载链接
		if (StringUtils.isBlank(ossUrl)) {
			ossUrl = audioFileUrl;
		}
		
		return ossUrl;
	}
	
	/**
	 * 当 statusName 关机,拒接,空号,忙音,停机,无人接听时,不使用回调状态值 TODO 和前端确认 statusName 的值 @谢开宇
	 * @param statusName
	 * @return
	 */
	private boolean flag(String statusName) {
		// 关机,拒接,空号,忙音,停机,无人接听
		return "poweroff".equalsIgnoreCase(statusName) || "reject".equalsIgnoreCase(statusName)
				|| "emptynumber".equalsIgnoreCase(statusName) || "busy".equalsIgnoreCase(statusName)
				|| "stop".equalsIgnoreCase(statusName) || "noanswer".equals(statusName);
	}
	
	/**
	 * 插入回调信息
	 * @param result ConvertResult 对象
	 */
	private void saveCallInfo(ConvertResult result) {
		Long virtualDoctorId = doctorMapper.getDoctorIdByMobile(result.getCalledNo());
		if (virtualDoctorId == null) {
			virtualDoctorId = 0L;
		}
		
		VirtualDoctorCallInfoParams params = new VirtualDoctorCallInfoParams();
		params.setSinToken(result.getSinToken());
		params.setType(result.getType());
		params.setMobile(result.getCalledNo());
		params.setCallUrl(result.getMonitorFilenameUrl());
		params.setStatus(result.getStatus());
		params.setStatusName(result.getStatusName());
		if (StringUtils.isBlank(result.getVisitTime())) {
			params.setVisitTime(null);
		} else {
			params.setVisitTime(result.getVisitTime());
		}
		params.setCallTime(result.getCallTime());
		params.setVirtualDoctorId(virtualDoctorId);
		
		callInfoMapper.saveVirtualDoctorCallInfo(params);
	}
	
	/**
	 * 根据 callId 更新 statusName, callOssUrl
	 * @param callOssUrl OSS URL
	 * @param statusName 状态名
	 * @param callId 打电话记录主键
	 * @param callTime 通话时长
	 */
	private void updateUrl(String callOssUrl, Integer status, String statusName, Long callId, Long callTime) {
		logger.info("callUrl:{},status:{}, statusName:{},id:{}", callOssUrl, status, statusName, callId);
		callInfoDao.updateUrlRefactor(callOssUrl, status, statusName, callId, callTime);
	}
	
}
