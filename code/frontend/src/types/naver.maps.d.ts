export {};

// Only the parts of the Naver Maps JS SDK this app uses (geocoding, map, marker).
declare global {
  namespace naver.maps {
    class LatLng {
      constructor(lat: number, lng: number);
    }

    class Map {
      constructor(element: HTMLElement, options: { center: LatLng; zoom: number });
    }

    class Marker {
      constructor(options: { position: LatLng; map: Map });
    }

    namespace Service {
      enum Status {
        OK = 200,
        ERROR = 500,
      }

      type GeocodeAddress = { x: string; y: string };
      type GeocodeResponse = { v2: { addresses: GeocodeAddress[] } };

      function geocode(
        options: { query: string },
        callback: (status: Status, response: GeocodeResponse) => void,
      ): void;
    }
  }

  interface Window {
    naver?: { maps: typeof naver.maps };
  }
}
