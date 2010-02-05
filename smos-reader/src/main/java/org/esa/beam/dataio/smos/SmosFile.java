/*
 * $Id: $
 *
 * Copyright (C) 2008 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.dataio.smos;


import com.bc.ceres.binio.*;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.MultiLevelSource;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import org.esa.beam.dataio.smos.dddb.BandDescriptor;
import org.esa.beam.dataio.smos.dddb.Dddb;
import org.esa.beam.dataio.smos.dddb.Family;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.smos.dgg.SmosDgg;
import org.esa.beam.util.io.FileUtils;

import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Represents a SMOS DGG product file.
 *
 * @author Ralf Quast
 * @version $Revision$ $Date$
 * @since SMOS-Box 1.0
 */
public class SmosFile extends ExplorerFile {

    private final SequenceData gridPointList;
    private final CompoundType gridPointType;
    private final int gridPointIdIndex;

    private volatile Future<GridPointInfo> gridPointInfoFuture;

    SmosFile(File hdrFile, File dblFile, DataFormat format) throws IOException {
        super(hdrFile, dblFile, format);

        gridPointList = getDataBlock().getSequence(SmosConstants.GRID_POINT_LIST_NAME);
        if (gridPointList == null) {
            throw new IllegalStateException(MessageFormat.format(
                    "SMOS File ''{0}'': Missing grid point list.", dblFile.getPath()));
        }

        gridPointType = (CompoundType) gridPointList.getType().getElementType();
        gridPointIdIndex = gridPointType.getMemberIndex(SmosConstants.GRID_POINT_ID_NAME);
    }

    public final int getGridPointCount() {
        return gridPointList.getElementCount();
    }

    public final int getGridPointId(int i) throws IOException {
        return gridPointList.getCompound(i).getInt(gridPointIdIndex);
    }

    public final int getGridPointSeqnum(int i) throws IOException {
        return SmosDgg.getGridPointSeqnum(getGridPointId(i));
    }

    public SequenceData getGridPointList() {
        return gridPointList;
    }

    private Future<GridPointInfo> getGridPointInfoFuture() {
        if (gridPointInfoFuture == null) {
            synchronized (this) {
                if (gridPointInfoFuture == null) {
                    gridPointInfoFuture = Executors.newSingleThreadExecutor().submit(new Callable<GridPointInfo>() {
                        @Override
                        public GridPointInfo call() throws IOException {
                            return createGridPointInfo();
                        }
                    });
                }
            }
        }

        return gridPointInfoFuture;
    }

    public final int getGridPointIndex(int seqnum) {
        try {
            return getGridPointInfoFuture().get().getGridPointIndex(seqnum);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    public CompoundType getGridPointType() {
        return gridPointType;
    }

    public CompoundData getGridPointData(int gridPointIndex) throws IOException {
        return gridPointList.getCompound(gridPointIndex);
    }

    @Override
    protected Area computeArea() throws IOException {
        final int latIndex = getGridPointType().getMemberIndex(SmosConstants.GRID_POINT_LAT_NAME);
        final int lonIndex = getGridPointType().getMemberIndex(SmosConstants.GRID_POINT_LON_NAME);
        final SequenceData gridPointList = getGridPointList();

        final Rectangle2D[] tileRectangles = new Rectangle2D[512];
        for (int i = 0; i < 32; ++i) {
            for (int j = 0; j < 16; ++j) {
                tileRectangles[i * 16 + j] = createTileRectangle(i, j);
            }
        }

        final Area envelope = new Area();
        for (int i = 0; i < gridPointList.getElementCount(); i++) {
            CompoundData compound = gridPointList.getCompound(i);
            double lon = compound.getFloat(lonIndex);
            double lat = compound.getFloat(latIndex);

            // normalisation to [-180, 180] necessary for some L1c test products
            if (lon > 180.0) {
                lon = lon - 360.0;
            }
            final double hw = 0.02;
            final double hh = 0.02;

            final double x = lon - hw;
            final double y = lat - hh;
            final double w = 0.04;
            final double h = 0.04;

            if (!envelope.contains(x, y, w, h)) {
                for (final Rectangle2D tileRectangle : tileRectangles) {
                    if (tileRectangle.intersects(x, y, w, h) && !envelope.contains(tileRectangle)) {
                        envelope.add(new Area(tileRectangle));
                        if (envelope.contains(x, y, w, h)) {
                            break;
                        }
                    }
                }
            }
        }

        return envelope;
    }

    @Override
    protected Product createProduct() throws IOException {
        final String productName = FileUtils.getFilenameWithoutExtension(getHdrFile());
        final String productType = getDataFormat().getName().substring(12, 22);
        final Dimension dimension = ProductHelper.getSceneRasterDimension();
        final Product product = new Product(productName, productType, dimension.width, dimension.height);

        product.setFileLocation(getDblFile());
        product.setPreferredTileSize(512, 512);
        ProductHelper.addMetadata(product.getMetadataRoot(), this);

        product.setGeoCoding(ProductHelper.createGeoCoding(dimension));
        addBands(product);

        return product;
    }

    protected void addBands(Product product) {
        final String formatName = getDataFormat().getName();
        final Family<BandDescriptor> descriptors = Dddb.getInstance().getBandDescriptors(formatName);

        if (descriptors != null) {
            for (final BandDescriptor descriptor : descriptors.asList()) {
                addBand(product, descriptor);
            }
        }
    }

    protected void addBand(Product product, BandDescriptor descriptor) {
        addBand(product, descriptor, getGridPointType());
    }

    protected final void addBand(Product product, BandDescriptor descriptor, CompoundType compoundType) {
        final int memberIndex = compoundType.getMemberIndex(descriptor.getMemberName());

        if (memberIndex >= 0) {
            final CompoundMember member = compoundType.getMember(memberIndex);

            final int dataType = ProductHelper.getDataType(member.getType());
            final Band band = product.addBand(descriptor.getBandName(), dataType);

            band.setScalingOffset(descriptor.getScalingOffset());
            band.setScalingFactor(descriptor.getScalingFactor());
            if (descriptor.hasFillValue()) {
                band.setNoDataValueUsed(true);
                band.setNoDataValue(descriptor.getFillValue());
            }
            if (!descriptor.getValidPixelExpression().isEmpty()) {
                band.setValidPixelExpression(descriptor.getValidPixelExpression());
            }
            if (!descriptor.getUnit().isEmpty()) {
                band.setUnit(descriptor.getUnit());
            }
            if (!descriptor.getDescription().isEmpty()) {
                band.setDescription(descriptor.getDescription());
            }
            if (descriptor.getFlagDescriptors() != null) {
                ProductHelper.addFlagsAndMasks(product, band, descriptor.getFlagCodingName(),
                        descriptor.getFlagDescriptors());
            }

            final ValueProvider valueProvider = createValueProvider(descriptor);
            band.setSourceImage(createSourceImage(band, valueProvider));
            band.setImageInfo(ProductHelper.createImageInfo(band, descriptor));
        }
    }

    protected ValueProvider createValueProvider(BandDescriptor descriptor) {
        switch (descriptor.getSampleModel()) {
            case 1:
                return new DefaultValueProvider(this, descriptor.getMemberName()) {
                    @Override
                    public int getValue(int gridPointIndex, int noDataValue) {
                        try {
                            final long value = getSmosFile().getGridPointData(gridPointIndex).getLong(getMemberIndex());
                            return (int) (value & 0x00000000FFFFFFFFL);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            case 2:
                return new DefaultValueProvider(this, descriptor.getMemberName()) {
                    @Override
                    public int getValue(int gridPointIndex, int noDataValue) {
                        try {
                            final long value = getSmosFile().getGridPointData(gridPointIndex).getLong(getMemberIndex());
                            return (int) (value >>> 32);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            default:
                return new DefaultValueProvider(this, descriptor.getMemberName());
        }
    }

    protected MultiLevelImage createSourceImage(final Band band, final ValueProvider valueProvider) {
        return new DefaultMultiLevelImage(createMultiLevelSource(band, valueProvider));
    }

    protected MultiLevelSource createMultiLevelSource(Band band, ValueProvider valueProvider) {
        return new SmosMultiLevelSource(band, valueProvider);
    }

    private GridPointInfo createGridPointInfo() throws IOException {
        int minSeqnum = getGridPointSeqnum(0);
        int maxSeqnum = minSeqnum;

        final int gridPointCount = getGridPointCount();
        for (int i = 1; i < gridPointCount; i++) {
            final int seqnum = getGridPointSeqnum(i);

            if (seqnum < minSeqnum) {
                minSeqnum = seqnum;
            } else {
                if (seqnum > maxSeqnum) {
                    maxSeqnum = seqnum;
                }
            }
        }

        final GridPointInfo gridPointInfo = new GridPointInfo(minSeqnum, maxSeqnum);
        Arrays.fill(gridPointInfo.indexes, -1);

        for (int i = 0; i < gridPointCount; i++) {
            gridPointInfo.indexes[getGridPointSeqnum(i) - minSeqnum] = i;
        }

        return gridPointInfo;
    }

    private Rectangle2D createTileRectangle(int i, int j) {
        final double w = 11.25;
        final double h = 11.25;
        final double x = w * i - 180.0 - w;
        final double y = 90.0 - h * (j + 1);

        return new Rectangle2D.Double(x, y, w, w);
    }

    private static final class GridPointInfo {

        final int minSeqnum;
        final int maxSeqnum;
        final int[] indexes;

        GridPointInfo(int minSeqnum, int maxSeqnum) {
            this.minSeqnum = minSeqnum;
            this.maxSeqnum = maxSeqnum;
            indexes = new int[maxSeqnum - minSeqnum + 1];
        }

        int getGridPointIndex(int seqnum) {
            if (seqnum < minSeqnum || seqnum > maxSeqnum) {
                return -1;
            }

            return indexes[seqnum - minSeqnum];
        }
    }
}
