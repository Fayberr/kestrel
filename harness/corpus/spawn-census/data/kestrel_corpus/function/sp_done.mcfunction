# spawn-census terminus (t=20s): marker only, no cleanup.
# Unlike block-entity-sleep (which kills its stage summons to return to a
# quiet world), the census population IS the measurement: removing it would
# destroy the steady state the tail is measuring. This stage proves the
# whole chain fired exactly once.
say [kestrel-corpus] spawn-census chain complete, population stable
