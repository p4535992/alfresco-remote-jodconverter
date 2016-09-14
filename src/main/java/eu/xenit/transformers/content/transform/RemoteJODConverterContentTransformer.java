/**
 * 
 */
package eu.xenit.transformers.content.transform;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.crypto.dsig.TransformException;

import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.repo.content.transform.AbstractContentTransformer2;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.TransformationOptions;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author Younes REGAIEG <younes@regaieg.info>
 *
 * This transformer relies on a remote JODConverter for office
 * documents transformations.
 */
public class RemoteJODConverterContentTransformer extends AbstractContentTransformer2 {

	private static final Log logger = LogFactory.getLog(RemoteJODConverterContentTransformer.class);
	private static final List<String> TARGET_MIMETYPES = Arrays
			.asList(new String[] { MimetypeMap.MIMETYPE_TEXT_PLAIN, MimetypeMap.MIMETYPE_PDF });

	private static final double MEGABYTES = 1024.0 * 1024.0;

	private static final String USAGE_PATTERN = "Content transformation has completed:\n" + "    Transformer: %s\n"
			+ "    Content Reader: %s\n" + "    Memory (MB): Used/Total/Maximum - %f/%f/%f\n" + "    Time Spent: %d ms";

	private static final List<String> SOURCE_MIMETYPES = Arrays.asList(new String[] {
			MimetypeMap.MIMETYPE_OPENDOCUMENT_PRESENTATION, MimetypeMap.MIMETYPE_OPENDOCUMENT_SPREADSHEET,
			MimetypeMap.MIMETYPE_OPENDOCUMENT_TEXT, MimetypeMap.MIMETYPE_OPENDOCUMENT_GRAPHICS,
			MimetypeMap.MIMETYPE_OPENDOCUMENT_IMAGE, MimetypeMap.MIMETYPE_OPENDOCUMENT_FORMULA,
			MimetypeMap.MIMETYPE_OPENDOCUMENT_PRESENTATION_TEMPLATE,
			MimetypeMap.MIMETYPE_OPENDOCUMENT_SPREADSHEET_TEMPLATE, MimetypeMap.MIMETYPE_OPENDOCUMENT_TEXT_TEMPLATE,
			MimetypeMap.MIMETYPE_OPENDOCUMENT_TEXT_MASTER, MimetypeMap.MIMETYPE_OPENDOCUMENT_TEXT_WEB,
			MimetypeMap.MIMETYPE_OPENDOCUMENT_GRAPHICS_TEMPLATE, MimetypeMap.MIMETYPE_OPENDOCUMENT_IMAGE_TEMPLATE,
			MimetypeMap.MIMETYPE_OPENDOCUMENT_FORMULA_TEMPLATE, MimetypeMap.MIMETYPE_OPENXML_PRESENTATION,
			MimetypeMap.MIMETYPE_OPENXML_SPREADSHEET, MimetypeMap.MIMETYPE_OPENXML_WORDPROCESSING,
			MimetypeMap.MIMETYPE_OPENXML_WORD_TEMPLATE, MimetypeMap.MIMETYPE_OPENOFFICE1_WRITER,
			MimetypeMap.MIMETYPE_OPENOFFICE1_IMPRESS, MimetypeMap.MIMETYPE_WORD, MimetypeMap.MIMETYPE_PPT,
			MimetypeMap.MIMETYPE_EXCEL });

	/**
	 * Can we do the requested transformation via remote JODConverter? We
	 * support transforming office documents to PDF or Text
	 */
	@Override
	public boolean isTransformableMimetype(String sourceMimetype, String targetMimetype,
			TransformationOptions options) {
		if (!SOURCE_MIMETYPES.contains(sourceMimetype)) {
			// The source isn't one of ours
			return false;
		}

		if (TARGET_MIMETYPES.contains(targetMimetype)) {
			// We can output to this
			return true;
		}
		// We support the source, but not the target
		return false;
	}

	@Override
	public String getComments(boolean available) {
		return getCommentsOnlySupports(SOURCE_MIMETYPES, TARGET_MIMETYPES, available);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.alfresco.repo.content.transform.AbstractContentTransformer2#
	 * transformInternal(org.alfresco.service.cmr.repository.ContentReader,
	 * org.alfresco.service.cmr.repository.ContentWriter,
	 * org.alfresco.service.cmr.repository.TransformationOptions)
	 */
	@Override
	protected void transformInternal(ContentReader reader, ContentWriter writer, TransformationOptions options)
			throws Exception {

		OutputStream os = writer.getContentOutputStream();
		String encoding = writer.getEncoding();
		String targetMimeType = writer.getMimetype();
		String sourceMimeType = reader.getMimetype();

		Writer ow = new OutputStreamWriter(os, encoding);

		InputStream is = null;

		long startTime = 0;
		try {
			is = reader.getContentInputStream();
			if (logger.isDebugEnabled()) {
				startTime = System.currentTimeMillis();
			}

			// TODO should externalize this in alfresco-global.properties
			String url = "https://jodconverter.dev.xenit.eu/converter/service";
			URL obj = new URL(url);
			HttpsURLConnection con = (HttpsURLConnection) obj.openConnection();

			// Set up limits -- TODO check if these values are taken into
			// consideration? I am getting the feeling timeouts are handled
			// on the dedicated thread for this transformation
			long readLimitTimeMs = options.getReadLimitTimeMs();
			if (readLimitTimeMs != -1)
				con.setConnectTimeout((int) readLimitTimeMs);
			long timeoutMs = options.getTimeoutMs();
			if (timeoutMs != -1)
				con.setReadTimeout((int) timeoutMs);
			// TODO Should I check for file size limits are?
			// Aren't they enforced by the AbstractContentTransformer2?

			// add request header
			con.setRequestMethod("POST");
			con.setRequestProperty("Content-Type", sourceMimeType);
			con.setRequestProperty("Accept", targetMimeType);

			// Send post request
			con.setDoOutput(true);
			DataOutputStream wr = new DataOutputStream(con.getOutputStream());

			// FIXME add support for 2GB+ content... Really ?
			IOUtils.copy(is, wr);
			wr.flush();
			wr.close();

			int responseCode = con.getResponseCode();
			if (responseCode != 200) {
				// Something went extremely wrong on remote server
				logger.error("Remote transformation failed, remote host returned response code :" + responseCode);
				if (logger.isDebugEnabled()) {
					logger.debug("Source MimeType : " + sourceMimeType);
					logger.debug("Target MimeType : " + targetMimeType);
					logger.debug("Source size : " + reader.getSize());
					logger.debug("Source ContentURL : " + reader.getContentUrl());
					logger.debug("Remote JODConverter instance : " + url);
				}
				throw new TransformException(con.getResponseMessage());
			}

			IOUtils.copy(con.getInputStream(), os);
		} finally {
			if (logger.isDebugEnabled()) {
				logger.debug(calculateMemoryAndTimeUsage(reader, startTime));
			}

			if (is != null) {
				try {
					is.close();
				} catch (Throwable e) {
				}
			}
			if (ow != null) {
				try {
					ow.close();
				} catch (Throwable e) {
				}
			}
			if (os != null) {
				try {
					os.close();
				} catch (Throwable e) {
				}
			}
		}
	}

	private String calculateMemoryAndTimeUsage(ContentReader reader, long startTime) {
		long endTime = System.currentTimeMillis();
		Runtime runtime = Runtime.getRuntime();
		long totalMemory = runtime.totalMemory();
		return String.format(USAGE_PATTERN, this.getClass().getName(), reader,
				(totalMemory - runtime.freeMemory()) / MEGABYTES, totalMemory / MEGABYTES,
				runtime.maxMemory() / MEGABYTES, (endTime - startTime));
	}

}
