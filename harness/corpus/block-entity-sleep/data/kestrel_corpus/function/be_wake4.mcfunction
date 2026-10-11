# Stage 4 (t=25s): chain terminus. Cleans up the stage-2/3 summons so the
# tail of the run returns to a quiet world (the furnace keeps smelting to
# completion on its own; the catalyst cursors decay on their own). Marker
# line lets the log confirm the full chain fired.
say [kestrel-corpus] sleep wake stage 4: chain complete, cleaning up
kill @e[type=iron_golem]
kill @e[type=ravager]
kill @e[type=allay]
say [kestrel-corpus] block-entity-sleep wake chain done
