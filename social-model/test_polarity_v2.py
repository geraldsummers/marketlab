import unittest
import polarity_v2
import event_conditioned as event
class PolarityV2Test(unittest.TestCase):
    def test_registered_family_is_four_variants(self):
        self.assertEqual(4,len([(h,e) for h in event.HORIZONS for e in event.ESTIMATORS]))
if __name__=="__main__": unittest.main()
