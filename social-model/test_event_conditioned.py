import importlib.util, sys, unittest
from pathlib import Path
import numpy as np
SPEC=importlib.util.spec_from_file_location("marketlab_event_conditioned",Path(__file__).with_name("event_conditioned.py")); event=importlib.util.module_from_spec(SPEC); sys.modules[SPEC.name]=event; SPEC.loader.exec_module(event)
class EventConditionedTest(unittest.TestCase):
    def test_family_size(self): self.assertEqual(8,len([(a,b,c) for a in event.SIGNED for b in event.HORIZONS for c in event.ESTIMATORS]))
    def test_holm(self): self.assertEqual([.04,.003,.04],event.holm([.04,.001,.02]))
    def test_complete_bars_required(self):
        bars={i*900_000:{"open":100.+i,"close":101.+i} for i in range(4)}; self.assertAlmostEqual(np.log(104/100),event.period_return(bars,0,1)); del bars[900_000]; self.assertIsNone(event.period_return(bars,0,1))
    def test_author_cap(self):
        rows=[{"indexedAtAvailabilityProxyEpochMillis":1000+i,"sourceEventId":str(i),"textSha256":str(i),"authorIdSha256":"a"} for i in range(5)]; self.assertEqual(3,len(event.filter_social(rows)))
if __name__=="__main__": unittest.main()
