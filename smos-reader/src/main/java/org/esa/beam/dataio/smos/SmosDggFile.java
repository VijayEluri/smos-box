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


import com.bc.ceres.binio.CompoundData;
import com.bc.ceres.binio.CompoundType;
import com.bc.ceres.binio.DataFormat;
import com.bc.ceres.binio.SequenceData;
import org.esa.beam.smos.dgg.SmosDgg;

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
 * Represents a SMOS product file providing data on the DGG.
 *
 * @author Ralf Quast
 * @version $Revision$ $Date$
 * @since SMOS-Box 1.0
 */
public class SmosDggFile extends SmosFile {

    private final SequenceData gridPointList;
    private final CompoundType gridPointType;
    private final int gridPointIdIndex;

    private volatile Future<GridPointInfo> gridPointInfoFuture;
    private volatile Future<Area> regionFuture;

    public SmosDggFile(File file, DataFormat format) throws IOException {
        super(file, format);

        gridPointList = getDataBlock().getSequence(SmosConstants.GRID_POINT_LIST_NAME);
        if (gridPointList == null) {
            throw new IllegalStateException(MessageFormat.format(
                    "SMOS File ''{0}'': Missing grid point list.", file.getPath()));
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
        return SmosDgg.smosGridPointIdToDggSeqnum(getGridPointId(i));
    }

    public SequenceData getGridPointList() {
        return gridPointList;
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

    public CompoundType getGridPointType() {
        return gridPointType;
    }

    public CompoundData getGridPointData(int gridPointIndex) throws IOException {
        return gridPointList.getCompound(gridPointIndex);
    }

    @Override
    public final Area getRegion() {
        try {
            return getRegionFuture().get();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private Future<Area> getRegionFuture() {
        if (regionFuture == null) {
            synchronized (this) {
                if (regionFuture == null) {
                    regionFuture = Executors.newSingleThreadExecutor().submit(new Callable<Area>() {
                        @Override
                        public Area call() throws IOException {
                            return computeRegion();
                        }
                    });
                }
            }
        }

        return regionFuture;
    }

    protected Area computeRegion() throws IOException {
        final int latIndex = getGridPointType().getMemberIndex(SmosConstants.GRID_POINT_LAT_NAME);
        final int lonIndex = getGridPointType().getMemberIndex(SmosConstants.GRID_POINT_LON_NAME);
        final SequenceData gridPointList = getGridPointList();

        final Rectangle2D[] tileRects = new Rectangle2D[512];
        for (int i = 0; i < 32; ++i) {
            for (int j = 0; j < 16; ++j) {
                tileRects[i * 16 + j] = createTileRectangle(i, j);
            }
        }

        final Area region = new Area();
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

            if (!region.contains(x, y, w, h)) {
                for (Rectangle2D tileRect : tileRects) {
                    if (tileRect.intersects(x, y, w, h) && !region.contains(tileRect)) {
                        region.add(new Area(tileRect));
                        if (region.contains(x, y, w, h)) {
                            break;
                        }
                    }
                }
            }
        }

        return region;
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

    private static Rectangle2D createTileRectangle(int i, int j) {
        final double w = 11.25;
        final double h = 11.25;
        final double x = w * i - 180.0 - w;
        final double y = 90.0 - h * (j + 1);

        return new Rectangle2D.Double(x, y, w, w);
    }

    private static class GridPointInfo {

        final int minSeqnum;
        final int maxSeqnum;
        final int[] indexes;

        GridPointInfo(int minSeqnum, int maxSeqnum) {
            this.minSeqnum = minSeqnum;
            this.maxSeqnum = maxSeqnum;
            indexes = new int[maxSeqnum - minSeqnum + 1];
        }

        final int getGridPointIndex(int seqnum) {
            if (seqnum < minSeqnum || seqnum > maxSeqnum) {
                return -1;
            }

            return indexes[seqnum - minSeqnum];
        }
    }
}
