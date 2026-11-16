package dev.earclean.preview;

public final class DeviceLogicTest {
 static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
 public static void main(String[] args) {
  int[][] readings={{80,52},{92,60},{87,57},{85,55},{66,43},{19,14},{14,10},{5,3},{40,27},{30,21}};
  for(int[] r:readings) check(BatteryEstimate.minutes(r[0])==r[1], "Battery display mismatch at "+r[0]);
  for(int p=1;p<=100;p++) check(BatteryEstimate.minutes(p)>=BatteryEstimate.minutes(p-1),"Battery estimate must be monotonic");
  check(BatteryEstimate.minutes(-1)==0 && BatteryEstimate.minutes(101)==65,"Battery boundaries");
  check(!PhotoPaging.commit(30,0,360,true),"Short slow swipe must spring back");
  check(PhotoPaging.commit(-90,0,360,true),"Long swipe must advance");
  check(PhotoPaging.commit(20,800,360,true),"Short fast fling must advance");
  check(!PhotoPaging.commit(20,-800,360,true),"Reversed velocity must not trigger a fling");
  check(!PhotoPaging.commit(200,1000,360,false),"No page beyond first/last photo");
  HardwareCheck hardware=new HardwareCheck();
  check(hardware.fps()==0 && hardware.frames==0,"No data must not count as hardware success");
  hardware.frame(1000,640,480,179); hardware.frame(1050,640,480,-179); hardware.frame(1100,640,480,-177);
  check(hardware.fps()==20 && hardware.angleTravel==4,"Correct frame rate and wrapped angle motion");
  check(hardware.gapAt(2100)==1000,"Detect an image stall after the last frame");
  System.out.println("PASS: supplied battery readings, monotonicity, photo release rules and hardware measurements");
 }
}
