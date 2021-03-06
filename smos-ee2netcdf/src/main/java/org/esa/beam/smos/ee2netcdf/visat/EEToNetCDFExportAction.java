package org.esa.beam.smos.ee2netcdf.visat;

import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.visat.actions.AbstractVisatAction;

public class EEToNetCDFExportAction extends AbstractVisatAction {

    @Override
    public void actionPerformed(CommandEvent event) {
        new EEToNetCDFExportDialog(getAppContext(), event.getCommand().getHelpId()).show();
    }
}
