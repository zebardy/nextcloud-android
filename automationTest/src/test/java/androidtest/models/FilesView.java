package androidtest.models;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.AndroidElement;
import io.appium.java_client.pagefactory.AndroidFindBy;
import io.appium.java_client.pagefactory.AppiumFieldDecorator;
import org.openqa.selenium.support.CacheLookup;
import org.openqa.selenium.support.PageFactory;
import androidtest.actions.Actions;

public class FilesView{
	final AndroidDriver driver;
	
	@CacheLookup
	@AndroidFindBy(id = "com.owncloud.android:id/list_root")
	private AndroidElement filesLayout;
	
	@CacheLookup
	@AndroidFindBy(id = "com.owncloud.android:id/upload_files_btn_upload")
	private AndroidElement uploadButton;
	
	private AndroidElement fileElement;
	
	public FilesView (AndroidDriver driver) {
		this.driver = driver;
		PageFactory.initElements(new AppiumFieldDecorator(driver), this);
	}
	
	public MainView clickOnUploadButton () {
		uploadButton.click();
		MainView mainView = new MainView (driver);
		return mainView;
	}
	
	//change to scrollTillFindElement
	public void scrollTillFindFile (String fileName) {
		fileElement = Actions.scrollTillFindElement (fileName,filesLayout,driver);
	}
	
	public void clickOnFileName (String fileName) {
		scrollTillFindFile(fileName);
		fileElement.click();
	}
}
