import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Lingoes LD2/LDF File Reader
 *
 * <pre>
 * Lingoes Format overview:
 *
 * General Information:
 * - Dictionary data are stored in deflate streams.
 * - Index group information is stored in an index array in the LD2 file itself.
 * - Numbers are using little endian byte order.
 * - Definitions and xml data have UTF-8 or UTF-16LE encodings.
 *
 * LD2 file schema:
 * - File Header
 * - File Description
 * - Additional Information (optional)
 * - Index Group (corresponds to definitions in dictionary)
 * - Deflated Dictionary Streams
 * -- Index Data
 * --- Offsets of definitions
 * --- Offsets of translations
 * --- Flags
 * --- References to other translations
 * -- Definitions
 * -- Translations (xml)
 *
 * TODO: find encoding / language fields to replace auto-detect of encodings
 *
 * </pre>
 *
 * @author keke
 *
 */
public class LingoesLd2Reader {
    private static final SensitiveStringDecoder[] AVAIL_ENCODINGS = {
            new SensitiveStringDecoder(Charset.forName("UTF-8")),
            new SensitiveStringDecoder(Charset.forName("UTF-16LE")),
            new SensitiveStringDecoder(Charset.forName("UTF-16BE")),
            new SensitiveStringDecoder(Charset.forName("EUC-JP")) };

    public static void main(final String[] args) throws IOException {
        final String ld2File = "E:/FTP/LingoesDict/Fundset Deutsch to Chinese.ld2";
        // read lingoes ld2 into byte array
        final ByteBuffer dataRawBytes;

        Path ld2FilePath = Paths.get(ld2File);

        RandomAccessFile file = new RandomAccessFile(ld2File, "r");
        FileChannel fChannel = file.getChannel();
        dataRawBytes = ByteBuffer.allocate((int) fChannel.size());
        fChannel.read(dataRawBytes);

        dataRawBytes.order(ByteOrder.LITTLE_ENDIAN);
        dataRawBytes.rewind();

        System.out.println("文件：" + ld2File);
        System.out.println("类型："
                + new String(dataRawBytes.array(), 0, 4, "ASCII"));
        System.out.println("版本：" + dataRawBytes.getShort(0x18) + "."
                + dataRawBytes.getShort(0x1A));
        System.out.println("ID: 0x"
                + Long.toHexString(dataRawBytes.getLong(0x1C)));

        final int offsetData = dataRawBytes.getInt(0x5C) + 0x60;
        if (dataRawBytes.limit() > offsetData) {
            System.out.println("简介地址：0x" + Integer.toHexString(offsetData));
            final int type = dataRawBytes.getInt(offsetData);
            System.out.println("简介类型：0x" + Integer.toHexString(type));
            final int offsetWithInfo = dataRawBytes.getInt(offsetData + 4)
                    + offsetData + 12;
            if (type == 3) {
                // without additional information
                LingoesLd2Reader.readDictionary(ld2File, dataRawBytes,
                        offsetData);
            } else if (dataRawBytes.limit() > (offsetWithInfo - 0x1C)) {
                LingoesLd2Reader.readDictionary(ld2File, dataRawBytes,
                        offsetWithInfo);
            } else {
                System.err.println("文件不包含字典数据。网上字典？");
            }
        } else {
            System.err.println("文件不包含字典数据。网上字典？");
        }
    }

    private static long decompress(final String inflatedFile,
                                         final ByteBuffer data, final int offset, final int length,
                                         final boolean append) throws IOException {
        final Inflater inflator = new Inflater();
        final InflaterInputStream in = new InflaterInputStream(
                new ByteArrayInputStream(data.array(), offset, length),
                inflator, 1024 * 8);
        final FileOutputStream out = new FileOutputStream(inflatedFile, append);

        LingoesLd2Reader.writeInputStream(in, out);
        final long bytesRead = inflator.getBytesRead();
        inflator.end();
        return bytesRead;
    }

    private static SensitiveStringDecoder[] detectEncodings(
            final ByteBuffer inflatedBytes, final int offsetWords,
            final int offsetXml, final int defTotal, final int dataLen,
            final int[] idxData, final String[] defData) {
        final int test = Math.min(defTotal, 10);
        for (int j = 0; j < LingoesLd2Reader.AVAIL_ENCODINGS.length; j++) {
            for (int k = 0; k < LingoesLd2Reader.AVAIL_ENCODINGS.length; k++) {
                try {
                    for (int i = 0; i < test; i++) {
                        LingoesLd2Reader.readDefinitionData(inflatedBytes,
                                offsetWords, offsetXml, dataLen,
                                LingoesLd2Reader.AVAIL_ENCODINGS[j],
                                LingoesLd2Reader.AVAIL_ENCODINGS[k], idxData,
                                defData, i);
                    }
                    System.out.println("词组编码："
                            + LingoesLd2Reader.AVAIL_ENCODINGS[j].name);
                    System.out.println("XML编码："
                            + LingoesLd2Reader.AVAIL_ENCODINGS[k].name);
                    return new SensitiveStringDecoder[] {
                            LingoesLd2Reader.AVAIL_ENCODINGS[j],
                            LingoesLd2Reader.AVAIL_ENCODINGS[k] };
                } catch (final Throwable e) {
                    // ignore
                }
            }
        }
        System.err.println("自动识别编码失败！选择UTF-16LE继续。");
        return new SensitiveStringDecoder[] {
                LingoesLd2Reader.AVAIL_ENCODINGS[1],
                LingoesLd2Reader.AVAIL_ENCODINGS[1] };
    }

    private static void extract(final String inflatedFile,
                                final String indexFile,
                                final String extractedOutputFile,
                                final String informationFile,
                                final int offsetDefs,
                                final int offsetXml)

            throws IOException, FileNotFoundException,
            UnsupportedEncodingException {
        System.out.println("写入'" + extractedOutputFile + "'。。。");
        int counter = 0;
        //解压后的文件
        RandomAccessFile file = new RandomAccessFile(inflatedFile, "r");
        //索引文件
//        final FileWriter indexWriter = new FileWriter(indexFile);
        //final Writer indexWriter = new BufferedWriter(new OutputStreamWriter( new FileOutputStream("indexFile"),"UTF-8"));
        final FileOutputStream indexWriter = new FileOutputStream(indexFile);

        //释意文件
        final FileWriter outputWriter = new FileWriter(extractedOutputFile);
        // 读解压后的文件
        final FileChannel fChannel = file.getChannel();
        final ByteBuffer dataRawBytes = ByteBuffer.allocate((int) fChannel.size());
        fChannel.read(dataRawBytes);
        fChannel.close();
        dataRawBytes.order(ByteOrder.LITTLE_ENDIAN);
        dataRawBytes.rewind();

        final int dataLen = 10;

        // 单词总数
        final int defTotal = (offsetDefs / dataLen) - 1;


        final int[] idxData = new int[6];
        final String[] defData = new String[2];

        final SensitiveStringDecoder[] encodings = LingoesLd2Reader
                .detectEncodings(dataRawBytes, offsetDefs, offsetXml, defTotal, dataLen, idxData, defData);

        dataRawBytes.position(8);
        int currDefPosition = 0;
        for (int i = 0; i < defTotal; i++) {
            LingoesLd2Reader.readDefinitionData(dataRawBytes, offsetDefs,
                    offsetXml, dataLen, encodings[0], encodings[1], idxData,
                    defData, i);

            //释意写入索引文件

            indexWriter.write(defData[0].getBytes("UTF-8"));
            //写入\0分隔
            indexWriter.write(0);
            //写入位置
            byte[] positionIntegerByte = ByteBuffer.allocate(4).putInt(currDefPosition).array();
            indexWriter.write(positionIntegerByte);

            int defintionNumOfBytes = defData[1].getBytes("UTF-8").length;

            byte[] definitionLengthIntegerByte = ByteBuffer.allocate(4).putInt(defintionNumOfBytes).array();

            indexWriter.write(definitionLengthIntegerByte);

            currDefPosition += defintionNumOfBytes;
            outputWriter.write(defData[1]);

            //System.out.println(defData[0] + " = " + defData[1]);
            counter++;
        }

        // 给出最后的information文件
        File idxFile = new File(indexFile);
        long idxFileSize = idxFile.length();
        FileWriter infomationFileWriter = new FileWriter(informationFile);
        String fileName = idxFile.getName();
        String dictName = fileName.substring(0,fileName.lastIndexOf("."));
        infomationFileWriter.write(
                "StartDict's dict ifo file\n" +
                "version=2.4.2\n" +
                "wordcount=" + counter + "\n" +
                "idxfilesize="+idxFileSize + "\n" +
                "bookname="+dictName.substring(0,dictName.lastIndexOf(".")));
        infomationFileWriter.flush();
        indexWriter.flush();
        System.out.println("成功读出" + counter + "组数据。");
    }

    private static void getIdxData(final ByteBuffer dataRawBytes,
                                         final int position, final int[] wordIdxData) {
        dataRawBytes.position(position);
        wordIdxData[0] = dataRawBytes.getInt();
        wordIdxData[1] = dataRawBytes.getInt();
        wordIdxData[2] = dataRawBytes.get() & 0xff;
        wordIdxData[3] = dataRawBytes.get() & 0xff;
        wordIdxData[4] = dataRawBytes.getInt();
        wordIdxData[5] = dataRawBytes.getInt();
    }

    private static void inflate(final ByteBuffer dataRawBytes,
                                      final List<Integer> deflateStreams, final String inflatedFile) {
        System.out.println("解压缩'" + deflateStreams.size() + "'个数据流至'"
                + inflatedFile + "'。。。");
        final int startOffset = dataRawBytes.position();
        int offset = -1;
        int lastOffset = startOffset;
        boolean append = false;
        try {
            for (final Integer offsetRelative : deflateStreams) {
                offset = startOffset + offsetRelative.intValue();
                LingoesLd2Reader.decompress(inflatedFile, dataRawBytes,
                        lastOffset, offset - lastOffset, append);
                append = true;
                lastOffset = offset;
            }
        } catch (final Throwable e) {
            System.err.println("解压缩失败: 0x" + Integer.toHexString(offset) + ": "
                    + e.toString());
        }
    }

    private static void readDefinitionData(
            final ByteBuffer inflatedBytes, final int offsetWords,
            final int offsetXml, final int dataLen,
            final SensitiveStringDecoder wordStringDecoder,
            final SensitiveStringDecoder xmlStringDecoder, final int[] idxData,
            final String[] defData, final int i) {
        LingoesLd2Reader.getIdxData(inflatedBytes, dataLen * i, idxData);
        int lastWordPos = idxData[0];
        int lastXmlPos = idxData[1];
        // final int flags = idxData[2];
        int refs = idxData[3];
        final int currentWordOffset = idxData[4];
        int currenXmlOffset = idxData[5];

        String xml = LingoesLd2Reader.strip(new String(xmlStringDecoder.decode(
                inflatedBytes.array(), offsetXml + lastXmlPos, currenXmlOffset
                        - lastXmlPos)));
        while (refs-- > 0) {
            final int ref = inflatedBytes.getInt(offsetWords + lastWordPos);
            LingoesLd2Reader.getIdxData(inflatedBytes, dataLen * ref, idxData);
            lastXmlPos = idxData[1];
            currenXmlOffset = idxData[5];
            if (xml.isEmpty()) {
                xml = LingoesLd2Reader.strip(new String(xmlStringDecoder
                        .decode(inflatedBytes.array(), offsetXml + lastXmlPos,
                                currenXmlOffset - lastXmlPos)));
            } else {
                xml = LingoesLd2Reader.strip(new String(xmlStringDecoder
                        .decode(inflatedBytes.array(), offsetXml + lastXmlPos,
                                currenXmlOffset - lastXmlPos)))
                        + ", " + xml;
            }
            lastWordPos += 4;
        }
        defData[1] = xml;

        final String word = new String(wordStringDecoder.decode(
                inflatedBytes.array(), offsetWords + lastWordPos,
                currentWordOffset - lastWordPos));
        defData[0] = word;
    }

    private static void readDictionary(final String ld2File,
                                       final ByteBuffer dataRawBytes,
                                       final int offsetWithIndex)
            throws IOException, FileNotFoundException, UnsupportedEncodingException {
        System.out.println("词典类型：0x"
                + Integer.toHexString(dataRawBytes.getInt(offsetWithIndex)));
        final int limit = dataRawBytes.getInt(offsetWithIndex + 4)
                + offsetWithIndex + 8;
        final int offsetIndex = offsetWithIndex + 0x1C;
        final int offsetCompressedDataHeader = dataRawBytes
                .getInt(offsetWithIndex + 8) + offsetIndex;
        final int inflatedWordsIndexLength = dataRawBytes
                .getInt(offsetWithIndex + 12);
        final int inflatedWordsLength = dataRawBytes
                .getInt(offsetWithIndex + 16);
        final int inflatedXmlLength = dataRawBytes.getInt(offsetWithIndex + 20);
        final int definitions = (offsetCompressedDataHeader - offsetIndex) / 4;
        final List<Integer> deflateStreams = new ArrayList<Integer>();
        dataRawBytes.position(offsetCompressedDataHeader + 8);
        int offset = dataRawBytes.getInt();
        while ((offset + dataRawBytes.position()) < limit) {
            offset = dataRawBytes.getInt();
            deflateStreams.add(Integer.valueOf(offset));
        }
        final int offsetCompressedData = dataRawBytes.position();
        System.out.println("索引词组数目：" + definitions);
        System.out.println("索引地址/大小：0x" + Integer.toHexString(offsetIndex)
                + " / " + (offsetCompressedDataHeader - offsetIndex) + " B");
        System.out.println("压缩数据地址/大小：0x"
                + Integer.toHexString(offsetCompressedData) + " / "
                + (limit - offsetCompressedData) + " B");
        System.out.println("词组索引地址/大小（解压缩后）：0x0 / " + inflatedWordsIndexLength
                + " B");
        System.out.println("词组地址/大小（解压缩后）：0x"
                + Integer.toHexString(inflatedWordsIndexLength) + " / "
                + inflatedWordsLength + " B");
        System.out.println("XML地址/大小（解压缩后）：0x"
                + Integer.toHexString(inflatedWordsIndexLength
                + inflatedWordsLength) + " / " + inflatedXmlLength
                + " B");
        System.out
                .println("文件大小（解压缩后）："
                        + ((inflatedWordsIndexLength + inflatedWordsLength + inflatedXmlLength) / 1024)
                        + " KB");
        final String inflatedFile = ld2File + ".inflated";
        LingoesLd2Reader.inflate(dataRawBytes, deflateStreams, inflatedFile);

        if (new File(inflatedFile).isFile()) {
            final String indexFile = ld2File + ".idx";
            final String extractedOutputFile = ld2File + ".dict";
            final String infomationFile = ld2File + ".ifo";
            dataRawBytes.position(offsetIndex);
            final int[] idxArray = new int[definitions];
            for (int i = 0; i < definitions; i++) {
                idxArray[i] = dataRawBytes.getInt();
            }
            LingoesLd2Reader.extract(inflatedFile, indexFile, extractedOutputFile,infomationFile, inflatedWordsIndexLength,
                    inflatedWordsIndexLength
                            + inflatedWordsLength);
        }
    }

    private static String strip(final String xml) {
        int open = 0;
        int end = 0;
        if ((open = xml.indexOf("<![CDATA[")) != -1) {
            if ((end = xml.indexOf("]]>", open)) != -1) {
                return xml.substring(open + "<![CDATA[".length(), end)
                        .replace('\t', ' ').replace('\n', ' ')
                        .replace('\u001e', ' ').replace('\u001f', ' ');
            }
        } else if ((open = xml.indexOf("<Ô")) != -1) {
            if ((end = xml.indexOf("</Ô", open)) != -1) {
                open = xml.indexOf(">", open + 1);
                return xml.substring(open + 1, end).replace('\t', ' ')
                        .replace('\n', ' ').replace('\u001e', ' ')
                        .replace('\u001f', ' ');
            }
        } else {
            final StringBuilder sb = new StringBuilder();
            end = 0;
            open = xml.indexOf('<');
            do {
                if ((open - end) > 1) {
                    sb.append(xml.substring(end + 1, open));
                }
                open = xml.indexOf('<', open + 1);
                end = xml.indexOf('>', end + 1);
            } while ((open != -1) && (end != -1));
            return sb.toString().replace('\t', ' ').replace('\n', ' ')
                    .replace('\u001e', ' ').replace('\u001f', ' ');
        }
        return "";
    }

    private static void writeInputStream(final InputStream in,
                                               final OutputStream out) throws IOException {
        final byte[] buffer = new byte[1024 * 8];
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
    }

    private static class SensitiveStringDecoder {
        final String name;
        private final CharsetDecoder cd;

        SensitiveStringDecoder(final Charset cs) {
            this.cd = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            this.name = cs.name();
        }

        char[] decode(final byte[] ba, final int off, final int len) {
            final int en = (int) (len * (double) this.cd.maxCharsPerByte());
            final char[] ca = new char[en];
            if (len == 0) {
                return ca;
            }
            this.cd.reset();
            final ByteBuffer bb = ByteBuffer.wrap(ba, off, len);
            final CharBuffer cb = CharBuffer.wrap(ca);
            try {
                CoderResult cr = this.cd.decode(bb, cb, true);
                if (!cr.isUnderflow()) {
                    cr.throwException();
                }
                cr = this.cd.flush(cb);
                if (!cr.isUnderflow()) {
                    cr.throwException();
                }
            } catch (final CharacterCodingException x) {
                // Substitution is always enabled,
                // so this shouldn't happen
                throw new Error(x);
            }
            return SensitiveStringDecoder.safeTrim(ca, cb.position());
        }

        private static char[] safeTrim(final char[] ca, final int len) {
            if (len == ca.length) {
                return ca;
            } else {
                return Arrays.copyOf(ca, len);
            }
        }
    }
}