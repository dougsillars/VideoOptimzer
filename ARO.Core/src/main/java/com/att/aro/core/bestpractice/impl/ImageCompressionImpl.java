/*
 *  Copyright 2014 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.att.aro.core.bestpractice.impl;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.att.aro.core.ApplicationConfig;
import com.att.aro.core.ILogger;
import com.att.aro.core.bestpractice.IBestPractice;
import com.att.aro.core.bestpractice.pojo.AbstractBestPracticeResult;
import com.att.aro.core.bestpractice.pojo.BPResultType;
import com.att.aro.core.bestpractice.pojo.ImageCompressionEntry;
import com.att.aro.core.bestpractice.pojo.ImageCompressionResult;
import com.att.aro.core.fileio.IFileManager;
import com.att.aro.core.model.InjectLogger;
import com.att.aro.core.packetanalysis.pojo.HttpDirection;
import com.att.aro.core.packetanalysis.pojo.HttpRequestResponseInfo;
import com.att.aro.core.packetanalysis.pojo.PacketAnalyzerResult;
import com.att.aro.core.packetanalysis.pojo.Session;
import com.att.aro.core.util.Util;

//FIXME ADD UNIT TESTS
public class ImageCompressionImpl implements IBestPractice {
	enum Quality {
		MID(85), LOW(70);
		private int percent;

		private Quality(int percent) {
			this.percent = percent;
		}

		public int getPercent() {
			return percent;
		}

		public float getFraction() {
			return ((float) percent) / 100;
		}

		public String getFileDesc() {
			return "_compressed_" + getPercent() + ".0";
		}
	}

	@InjectLogger
	private static ILogger LOGGER;

	static String FILE_DESC = "_compressed_";

	@Value("${imageCompression.title}")
	private String overviewTitle;

	@Value("${imageCompression.detailedTitle}")
	private String detailTitle;

	@Value("${imageCompression.desc}")
	private String aboutText;

	@Value("${imageCompression.url}")
	private String learnMoreUrl;

	@Value("${imageCompression.pass}")
	private String textResultPass;

	@Value("${imageCompression.results}")
	private String textResults;

	@Autowired
	private IFileManager filemanager;

	long orginalImagesSize = 0L;
	long midQualImgsSize = 0L;

	@Override
	public AbstractBestPracticeResult runTest(PacketAnalyzerResult tracedata) {
		ImageCompressionResult result = new ImageCompressionResult();
		String tracePath = tracedata.getTraceresult().getTraceDirectory() + System.getProperty("file.separator");
		String imagePath = tracePath + "Image" + System.getProperty("file.separator");
		orginalImagesSize = 0L;
		midQualImgsSize = 0L;

		boolean isImagesCompressed = isImagesCompressed(imagePath);

		if (!isImagesCompressed) {
			compressImages(tracedata, imagePath);
		}
		List<ImageCompressionEntry> entrylist = getEntryList(imagePath, tracedata);

		result.setResults(entrylist);
		String text = "";
		String totalSavings = "";
		if (entrylist.isEmpty()) {
			result.setResultType(BPResultType.PASS);
			text = MessageFormat.format(textResultPass, entrylist.size());
			result.setResultText(text);
		} else {
			result.setResultType(BPResultType.FAIL);
			long savings = orginalImagesSize - midQualImgsSize;
			if (savings > 1024) {
				totalSavings = Long.toString(savings / 1024) + " KB";
			} else {
				totalSavings = Long.toString(savings) + " B";
			}
			text = MessageFormat.format(textResults, totalSavings);
			result.setResultText(text);
		}
		result.setAboutText(aboutText);
		result.setDetailTitle(detailTitle);
		result.setLearnMoreUrl(MessageFormat.format(learnMoreUrl, 
													ApplicationConfig.getInstance().getAppUrlBase()));
		result.setOverviewTitle(overviewTitle);
		return result;
	}

	private boolean isImagesCompressed(String imagePath) {
		if (filemanager.directoryExist(imagePath)) {
			File folder = new File(imagePath);
			File[] listOfFiles = folder.listFiles();
			if (listOfFiles != null && listOfFiles.length != 0) {
				for (int i = 0; i < listOfFiles.length; i++) {
					if (listOfFiles[i].isFile() && listOfFiles[i].getName().contains(FILE_DESC)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private List<ImageCompressionEntry> getEntryList(String imageFolderPath, PacketAnalyzerResult tracedta) {
		String originalImage = "";
		String midCompressedImagePath = "";
		String logCompressedImagepath = "";

		long lowQualityImgSize = 0L;
		long midQualityImgSize = 0L;
		long orgImageSize = 0L;
		String imgExtn = "";
		String units = " KB";
		String orgImgSize = "";
		String midQualityImageSize = "";
		String lowQualityImageSize = "";
		List<ImageCompressionEntry> entryList = new ArrayList<ImageCompressionEntry>();
		for (Session session : tracedta.getSessionlist()) {
			for (HttpRequestResponseInfo reqResp : session.getRequestResponseInfo()) {

				if (reqResp.getDirection() == HttpDirection.RESPONSE && reqResp.getContentType() != null
						&& reqResp.getContentType().contains("image/")) {

					originalImage = extractFullNameFromRRInfo(reqResp);
					int pos = originalImage.lastIndexOf(".");
					imgExtn = originalImage.substring(pos + 1, originalImage.length());
					if (imgExtn.equalsIgnoreCase("jpeg") || imgExtn.equalsIgnoreCase("jpg")) {

						midCompressedImagePath = imageFolderPath
								+ originalImage.substring(0, originalImage.lastIndexOf(".")) + Quality.MID.getFileDesc()
								+ originalImage.substring(originalImage.lastIndexOf("."), originalImage.length());

						logCompressedImagepath = imageFolderPath
								+ originalImage.substring(0, originalImage.lastIndexOf(".")) + Quality.LOW.getFileDesc()
								+ originalImage.substring(originalImage.lastIndexOf("."), originalImage.length());
						orgImageSize = new File(imageFolderPath + originalImage).length();
						midQualityImgSize = new File(midCompressedImagePath).length();
						lowQualityImgSize = new File(logCompressedImagepath).length();
						if (midQualityImgSize > 0 && ((orgImageSize - midQualityImgSize) * 100 / orgImageSize >= 15)
								&& lowQualityImgSize < orgImageSize) {

							orginalImagesSize = orginalImagesSize + orgImageSize;
							midQualImgsSize = midQualImgsSize + midQualityImgSize;

							if (orgImageSize > 1024) {
								orgImgSize = Long.toString(orgImageSize / 1024) + units;
							} else {
								orgImgSize = Long.toString(orgImageSize) + " B";
							}
							if (midQualityImgSize > 1024) {
								midQualityImageSize = Long.toString(midQualityImgSize / 1024) + units;
							} else {
								midQualityImageSize = Long.toString(midQualityImgSize) + " B";
							}
							if (lowQualityImgSize > 1024) {
								lowQualityImageSize = Long.toString(lowQualityImgSize / 1024) + units;
							} else {
								lowQualityImageSize = Long.toString(lowQualityImgSize) + " B";
							}

							entryList.add(new ImageCompressionEntry(reqResp, session.getDomainName(),
									imageFolderPath + originalImage, orgImgSize, midQualityImageSize,
									lowQualityImageSize));
						}

					}
				}
			}
		}
		return entryList;
	}

	private void compressImages(PacketAnalyzerResult tracedata, final String imagePath) {
		ExecutorService exec = Executors.newFixedThreadPool(5);
		for (final Session session : tracedata.getSessionlist()) {
			for (final HttpRequestResponseInfo req : session.getRequestResponseInfo()) {
				if (req.getDirection() == HttpDirection.RESPONSE && req.getContentType() != null
						&& req.getContentType().contains("image/")) {
					final String extractedImage = extractFullNameFromRRInfo(req);

					File imgFile = new File(imagePath + extractedImage);
					if (imgFile.exists() && !imgFile.isDirectory()) {
						int posExtn = extractedImage.lastIndexOf(".");
						String imgExtn = extractedImage.substring(posExtn + 1, extractedImage.length());
						if (imgExtn.equalsIgnoreCase("jpeg") || imgExtn.equalsIgnoreCase("jpg")) {
							exec.submit(new Runnable() {
								@Override
								public void run() {
									compressImage(imagePath, extractedImage);
								}
							});
						}
					}
				}
			}
		}
		try {// Time out after 10 minutes
			exec.shutdown();
			exec.awaitTermination(10, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	private void compressImage(String imgPath, String imgfile) {
		String compressedImageName = "";
		String orgImagePath = imgPath + imgfile;
		File orgImgFile = new File(orgImagePath);
		try (InputStream inputStrm = new FileInputStream(orgImgFile)) {
			BufferedImage buffImage = ImageIO.read(inputStrm);
			for (Quality qual : Quality.values()) {
				compressedImageName = imgPath + imgfile.substring(0, imgfile.lastIndexOf(".")) + qual.getFileDesc()
						+ "." + imgfile.substring(imgfile.lastIndexOf(".") + 1, imgfile.length());
				File compressedFile = new File(compressedImageName);
				ImageWriter writer = null;
				try (OutputStream outputStr = new FileOutputStream(compressedFile);
						ImageOutputStream imgOutputStrm = ImageIO.createImageOutputStream(outputStr)) {

					Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(
							imgfile.substring(imgfile.lastIndexOf(".") + 1, imgfile.length()));
					writer = (ImageWriter) writers.next();
					writer.setOutput(imgOutputStrm);
					ImageWriteParam param = writer.getDefaultWriteParam();
					param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
					param.setCompressionQuality((float) qual.getFraction());
					writer.write(null, new IIOImage(buffImage, null, null), param);
				} finally {
					if (writer != null) {
						writer.dispose();
					}
				}
			}
		} catch (IOException fileException) {
			LOGGER.error(fileException.toString(), fileException);
		}
	}

	private String extractFullNameFromRRInfo(HttpRequestResponseInfo hrri) {
		HttpRequestResponseInfo rsp = hrri.getAssocReqResp();
		String extractedImageName = "";
		String imageName = "";
		if (rsp != null) {
			String imagefromReq = rsp.getObjName();
			imageName = imagefromReq.substring(imagefromReq.lastIndexOf(Util.FILE_SEPARATOR) + 1);
			int pos = imageName.lastIndexOf("/") + 1;
			extractedImageName = imageName.substring(pos);
		}
		return extractedImageName;
	}

}
