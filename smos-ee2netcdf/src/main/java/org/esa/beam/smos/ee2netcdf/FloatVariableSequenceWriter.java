package org.esa.beam.smos.ee2netcdf;

import com.bc.ceres.binio.CompoundData;
import com.bc.ceres.binio.SequenceData;
import org.esa.beam.dataio.netcdf.nc.NVariable;
import ucar.ma2.Array;

import java.io.IOException;

public class FloatVariableSequenceWriter implements VariableWriter {

    private final Array array;
    private final NVariable variable;
    private final int memberIndex;

    public FloatVariableSequenceWriter(NVariable variable, int arraySize, int memberIndex) {
        this.variable = variable;
        this.memberIndex = memberIndex;
        array = Array.factory(new float[arraySize]);
    }

    @Override
    public void write(CompoundData gridPointData, SequenceData btDataList, int index) throws IOException {
        final float data = btDataList.getCompound(0).getFloat(memberIndex);
        array.setFloat(index, data);
    }

    @Override
    public void close() throws IOException {
        variable.writeFully(array);
    }
}
