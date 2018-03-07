package org.openntf.xsp.extlibx.bazaar.odpcompiler.util;

public enum ODSConstants {
	;

	public static final int SIZE_BYTE = 1;
	public static final int SIZE_WORD = 2;
	public static final int SIZE_DWORD = 4;
	public static final int SIZE_LSIG = SIZE_WORD            // Signature
	                             + SIZE_DWORD;                // Length
	public static final short SIG_CDFILEHEADER = 97;
	public static final int SIZE_CDFILEHEADER = SIZE_LSIG    // Header
	                             + SIZE_WORD                  // FilleExtLen
	                             + SIZE_DWORD                 // FileDataSize
	                             + SIZE_DWORD                 // SegCount
	                             + SIZE_DWORD                 // Flags
	                             + SIZE_DWORD;                // Reserved
	public static final short SIG_CDFILESEGMENT = 96;
	public static final int SIZE_CDFILESEGMENT = SIZE_LSIG   // Header
			                     + SIZE_WORD                  // DataSize
	                             + SIZE_WORD                  // SegSize
	                             + SIZE_DWORD                 // Flags
	                             + SIZE_DWORD;                // Reserved
	public static final short SIG_CDIMAGEHEADER = 125;
	public static final int SIZE_CDIMAGEHEADER = SIZE_LSIG   // Signature
                                 + SIZE_WORD                  // ImageType
                                 + SIZE_WORD                  // Width
                                 + SIZE_WORD                  // Height
                                 + SIZE_DWORD                 // ImageDataSize
                                 + SIZE_DWORD                 // SegCount
                                 + SIZE_DWORD                 // Flags
                                 + SIZE_DWORD;                // Reserved
	public static final short SIG_CDIMAGESEGMENT = 124;
	public static final int SIZE_CDIMAGESEGMENT = SIZE_LSIG  // Header
                                 + SIZE_WORD                  // DataSize
                                 + SIZE_WORD;                 // SegSize
	public static final int SIZE_RECTSIZE = SIZE_WORD        // width
			                     + SIZE_WORD;                 // height
	public static final int SIZE_CROPRECT = SIZE_WORD        // left
			                     + SIZE_WORD                  // top
			                     + SIZE_WORD                  // right
			                     + SIZE_WORD;                 // bottom
	public static final short SIG_CDGRAPHIC = 153;
	public static final int SIZE_CDGRAPHIC = SIZE_LSIG       // Signature
			                     + SIZE_RECTSIZE              // DestSize
			                     + SIZE_RECTSIZE              // CropSize
			                     + SIZE_CROPRECT              // CropOffset
			                     + SIZE_WORD                  // fResize
			                     + SIZE_BYTE                  // Version
			                     + SIZE_BYTE                  // bFlags
			                     + SIZE_WORD;                 // wReserved
	public static final byte CDGRAPHIC_VERSION3 = 2;
			                     
	
	// It appears that CDFILESEGMENTs cap out at 10240 bytes of data
	public static final int FILE_SEGMENT_SIZE_CAP = 10240;
	/** The amount of data to store in each CD record item */
	public static final int PER_FILE_ITEM_DATA_CAP = (SIZE_CDFILESEGMENT + FILE_SEGMENT_SIZE_CAP) * 2;
	/** The amount of data to store in each CD image record item */
	public static final int IMAGE_SEGMENT_SIZE_CAP = 10250;
	public static final int PER_IMAGE_ITEM_DATA_CAP = (SIZE_CDIMAGESEGMENT + IMAGE_SEGMENT_SIZE_CAP) * 2;
}
