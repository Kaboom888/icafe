/**
 * Copyright (c) 2014 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 *
 * Change History - most recent changes go on top of previous changes
 *
 * GIFFWriter.java
 *
 * Who   Date       Description
 * ====  =========  =================================================================
 * WY    22Apr2014  Added splitFramesEx() to split animated GIFs into separate GIFs
 * WY    20Apr2014  Added splitFrames() to split animated GIFs into frames
 * WY    16Apr2014  Added writeFrame() to support animated GIFs
 */

package cafe.image.gif;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

//import cafe.image.core.ImageMeta;
import cafe.image.core.ImageType;
import cafe.image.reader.GIFReader;
import cafe.image.writer.GIFWriter;
import cafe.image.writer.ImageWriter;
import cafe.string.StringUtils;

/**
 * GIF image tweaking tool
 * 
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 04/16/2014
 */
public class GIFTweaker {	
	// Data transfer object for multiple thread support
	private static class DataTransferObject {
		private byte[] header;	
		private byte[] logicalScreenDescriptor;
		private byte[] globalPalette;
		private byte[] imageDescriptor;
	}
	
	private static boolean copyFrame(InputStream is, OutputStream os, DataTransferObject DTO) throws IOException {
		// Copy global scope data
		os.write(DTO.header);
		os.write(DTO.logicalScreenDescriptor);
		
		if(DTO.globalPalette != null) os.write(DTO.globalPalette);
		
		int image_separator = 0;

		do {
		    image_separator = is.read();
		    if(image_separator == 0x3b) return false;
		    
			if(image_separator == -1) { // End of stream 
				System.out.println("Unexpected end of stream!");
				return false;
			}
		    
			if (image_separator == 0x21) // (!) Extension Block
			{
				int func = is.read();
				os.write(0x21);
				os.write(func);
				
				int len = 0;
				
				while((len = is.read()) > 0) {
					os.write(len);
					byte[] block = new byte[len];
					is.read(block);
					os.write(block);
				}
				
				os.write(0);		
			}
		} while(image_separator != 0x2c); // ","

		readImageDescriptor(is, DTO);
		
		os.write(0x2c);
		os.write(DTO.imageDescriptor);

		if((DTO.imageDescriptor[8]&0x80) == 0x80)
	    {
			int bitsPerPixel = (DTO.imageDescriptor[8]&0x07)+1;
			int colorsUsed = (1<<bitsPerPixel);

			byte[] localPalette = new byte[3*colorsUsed];
		    is.read(localPalette);
		    os.write(localPalette);
		}		
		
		// Copy compressed image data
		os.write(is.read());
		int len = 0;
		
		while((len = is.read()) > 0) {
			os.write(len);
			byte[] block = new byte[len];
			is.read(block);
			os.write(block);
		}
		
		os.write(0);		
		os.write(0x3b);
		
		os.close();
		
		return true;
	}
	
	private static void readGlobalPalette(InputStream is, int num_of_color, DataTransferObject DTO) throws IOException {
		 DTO.globalPalette = new byte[num_of_color*3];
		 is.read(DTO.globalPalette);
	}
	
	private static void readHeader(InputStream is, DataTransferObject DTO) throws IOException {
		DTO.header = new byte[6];
		is.read(DTO.header);
	}
	
	private static void readImageDescriptor(InputStream is, DataTransferObject DTO) throws IOException {
		DTO.imageDescriptor = new byte[9];
	    is.read(DTO.imageDescriptor);
	}
	
	private static void readLSD(InputStream is, DataTransferObject DTO) throws IOException {
		DTO.logicalScreenDescriptor = new byte[7];
		is.read(DTO.logicalScreenDescriptor);
	}
	
	/**
	 * Split a multiple frame GIF into individual frames and save them as GIF images.
	 * The split is "literally" since no frame decoding and other operations involved.
	 * This sometimes lead to funny looking GIFs.
	 * 
	 * @param is input GIF image stream
	 * @param outputFilePrefix optional output file name prefix  
	 */	
	public static void splitFrames(InputStream is, String outputFilePrefix) throws Exception {
		// Create a new data transfer object to hold data
		DataTransferObject DTO = new DataTransferObject();
		
		readHeader(is, DTO);
		readLSD(is, DTO);
		
		if((DTO.logicalScreenDescriptor[4]&0x80) == 0x80) // A global color map is present 
		{
			int bitsPerPixel = (DTO.logicalScreenDescriptor[4]&0x07)+1;
			int colorsUsed = (1 << bitsPerPixel);
			readGlobalPalette(is, colorsUsed, DTO);			
		}
		
		int frameCount = 0;
		String outFileName;
		FileOutputStream os;
		
		do {
			outFileName = StringUtils.isNullOrEmpty(outputFilePrefix)?"frame_" + frameCount++:outputFilePrefix + "_frame_" + frameCount++;
			os = new FileOutputStream(outFileName + ".gif");
		} while(copyFrame(is, os, DTO));
		
		os.close(); // Close the last file stream in order to delete it
		// Delete the last file which is invalid
		new File(outFileName + ".gif").delete();
	}
	
	/** 
	 * Split animated GIF to GIF images
	 * 
	 * @param is input animated GIF stream
	 * @param writer ImageWriter for the output frame
	 * @param outputFilePrefix optional prefix for the output image
	 * @throws Exception
	 */
	public static void splitFramesEx(InputStream is, ImageWriter writer, String outputFilePrefix) throws Exception {
		// Create a GIFReader to read GIF frames	
		GIFReader reader = new GIFReader();
		// Create a GIFWriter or other writers to write the frames
		ImageType imageType = writer.getImageType();
		// This single call will trigger the reading of the global scope data		
		BufferedImage bi = reader.getFrameAsBufferedImage(is);
		// After reading the global scope data, we can retrieve values such logical screen width and height etc.
		int logicalScreenWidth = reader.getLogicalScreenWidth();
		int logicalScreenHeight = reader.getLogicalScreenHeight();
		// Then create a BufferedImage with the width and height of the logical screen and draw frames upon it
		BufferedImage baseImage = new BufferedImage(logicalScreenWidth, logicalScreenHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = baseImage.createGraphics();
		//g.setColor(reader.getBackgroundColor());
		//g.fillRect(0, 0, logicalScreenWidth, logicalScreenHeight);
		
		//ImageMeta.ImageMetaBuilder builder = new ImageMeta.ImageMetaBuilder();
		//builder.transparent(reader.isTransparent()).transparentColor(reader.getBackgroundColor().getRGB());
  
		int frameCount = 0;		
		String baseFileName = StringUtils.isNullOrEmpty(outputFilePrefix)?"frame_":outputFilePrefix + "_frame_";
		
		while(bi != null) {
			int image_x = reader.getImageX();
			int image_y = reader.getImageY();
			/* Backup the area to be override by this frame */
			int imageWidth = bi.getWidth();
			int imageHeight = bi.getHeight();
			Rectangle area = new Rectangle(image_x, image_y, imageWidth, imageHeight);
			// Create a backup bufferedImage
			BufferedImage backup = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
			backup.setData(baseImage.getData(area));
			/* End of backup */
			// Draw this frame to the base
			g.drawImage(bi, image_x, image_y, null);
			// Write the base to file
			String outFileName = baseFileName + frameCount++;
			FileOutputStream os = new FileOutputStream(outFileName + "." + imageType.getExtension());			
			//writer.setImageMeta(builder.build());
			writer.write(baseImage, os);
			// Assume no transparency
			//builder.transparent(false);
			// Check about disposal method to take action accordingly
			if(reader.getDisposalMethod() == 1 || reader.getDisposalMethod() == 0) // Leave in place or unspecified
				; // No action needed
			else if(reader.getDisposalMethod() == 2) { // Restore to background
				baseImage = new BufferedImage(logicalScreenWidth, logicalScreenHeight, BufferedImage.TYPE_INT_ARGB);
				g = baseImage.createGraphics();
				//g.setColor(reader.getBackgroundColor());
				//g.fillRect(0, 0, logicalScreenWidth, logicalScreenHeight);
				//builder.transparent(true);
			} else if(reader.getDisposalMethod() == 3) { // Restore to previous
				g.drawImage(backup, image_x, image_y, null);			
			} else { // To be defined - should never come here
				baseImage = new BufferedImage(logicalScreenWidth, logicalScreenHeight, BufferedImage.TYPE_INT_ARGB);
				g = baseImage.createGraphics();
				//g.setColor(reader.getBackgroundColor());
				//g.fillRect(0, 0, logicalScreenWidth, logicalScreenHeight);
				//builder.transparent(true);
			}
			// Read another frame if we have more
			bi = reader.getFrameAsBufferedImage(is);
		}
	}
	
	/**
	 * Create animated GIFs from a series of BufferedImage
	 * 
	 * @param images an array of BufferedImage 
	 * @param os OutputStream to write the image
	 * @param delays delay times in millisecond between the frames
	 */
	public static void writeAnimatedGIF(BufferedImage[] images, int[] delays, OutputStream os) throws Exception {
		GIFWriter writer = new GIFWriter(); // We can set GIFOptions such as disposal method here
		writer.writeAnimatedGIF(images, delays, os);
	}
	
	private GIFTweaker() {}
}